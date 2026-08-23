package neuropublish.backend

import cats.effect.{IO, Ref}
import fs2.io.file.{Files, Path}
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import scala.concurrent.duration.*

/** Browser sessions: the cookie carries a random secret; only its SHA-256 is stored under
  * `<data>/sessions/<sha256>.json` with the user id and expiry.
  */
final case class SessionRecord(userId: String, createdAt: String, expiresAt: String)
object SessionRecord:
  given Codec[SessionRecord] = deriveCodec

final class Sessions(dir: Path):
  val lifetime: FiniteDuration = 24.hours
  private def file(hash: String) = dir / s"$hash.json"

  /** Returns the clear secret (for the cookie) — never stored. */
  def create(userId: String): IO[(String, Instant)] =
    for
      secret <- Secrets.token(32)
      now <- IO.realTimeInstant
      exp = now.plusMillis(lifetime.toMillis)
      _ <- JsonFiles.write(
        file(Secrets.sha256Hex(secret)),
        SessionRecord(userId, now.toString, exp.toString)
      )
    yield (secret, exp)

  def resolve(secret: String): IO[Option[String]] =
    val p = file(Secrets.sha256Hex(secret))
    JsonFiles.read[SessionRecord](p).flatMap {
      case None => IO.none
      case Some(s) =>
        IO.realTimeInstant.flatMap { now =>
          if Instant.parse(s.expiresAt).isAfter(now) then IO.pure(Some(s.userId))
          else Files[IO].deleteIfExists(p).as(None)
        }
    }

  def revoke(secret: String): IO[Unit] =
    Files[IO].deleteIfExists(file(Secrets.sha256Hex(secret))).void

/** User tokens minted by the device flow; `<data>/tokens/<sha256>.json`. */
final case class UserTokenRecord(userId: String, client: String, createdAt: String)
object UserTokenRecord:
  given Codec[UserTokenRecord] = deriveCodec

final class UserTokens(dir: Path):
  private def file(hash: String) = dir / s"$hash.json"
  def mint(userId: String, client: String): IO[String] =
    for
      secret <- Secrets.token(32).map("npu_" + _)
      now <- IO.realTimeInstant
      _ <- JsonFiles.write(
        file(Secrets.sha256Hex(secret)),
        UserTokenRecord(userId, client, now.toString)
      )
    yield secret
  def resolve(secret: String): IO[Option[UserTokenRecord]] =
    JsonFiles.read[UserTokenRecord](file(Secrets.sha256Hex(secret)))

/** RFC 8628 device authorization grants. In memory: a code lives ten minutes and the server that
  * issued it is the one polled.
  */
enum DeviceState:
  case Pending
  case Granted(userId: String)
  case Denied

final case class DeviceGrant(
    deviceCode: String,
    userCode: String,
    client: String,
    expiresAt: Instant,
    lastPolled: Option[Instant],
    state: DeviceState
)

final class DeviceFlow(grants: Ref[IO, Map[String, DeviceGrant]], tokens: UserTokens):
  val expiresIn: FiniteDuration = 10.minutes
  val interval: FiniteDuration = 5.seconds

  def start(client: String): IO[DeviceGrant] =
    for
      now <- IO.realTimeInstant
      dc <- Secrets.token(32)
      uc <- Secrets.userCode
      g = DeviceGrant(dc, uc, client, now.plusMillis(expiresIn.toMillis), None, DeviceState.Pending)
      _ <- grants.update(m =>
        m.filter(_._2.expiresAt.isAfter(now)) + (dc -> g)
      ) // also sweeps expired codes
    yield g

  enum Poll:
    case Pending
    case Granted(token: String, userId: String)
    case Denied
    case Expired

  /** Polling faster than `interval` is answered with `pending` without consulting the grant. A
    * granted code is consumed: the token is minted exactly once.
    */
  def poll(deviceCode: String): IO[Poll] =
    IO.realTimeInstant.flatMap { now =>
      grants.modify[Either[Poll, (String, String)]] { m =>
        m.get(deviceCode) match
          case None => (m, Left(Poll.Expired))
          case Some(g) if !g.expiresAt.isAfter(now) => (m - deviceCode, Left(Poll.Expired))
          case Some(g)
              if g.lastPolled.exists(t => now.toEpochMilli - t.toEpochMilli < interval.toMillis) =>
            (m.updated(deviceCode, g.copy(lastPolled = Some(now))), Left(Poll.Pending))
          case Some(g) =>
            g.state match
              case DeviceState.Pending =>
                (m.updated(deviceCode, g.copy(lastPolled = Some(now))), Left(Poll.Pending))
              case DeviceState.Denied => (m - deviceCode, Left(Poll.Denied))
              case DeviceState.Granted(uid) => (m - deviceCode, Right((g.client, uid)))
      }.flatMap {
        case Left(p) => IO.pure(p)
        case Right((client, uid)) => tokens.mint(uid, client).map(t => Poll.Granted(t, uid))
      }
    }

  /** Bind a user code to the approving user. Left = no such pending code. */
  def approve(userCode: String, userId: String): IO[Either[String, DeviceGrant]] =
    Secrets.normalizeUserCode(userCode) match
      case None => IO.pure(Left("malformed user code"))
      case Some(uc) =>
        IO.realTimeInstant.flatMap { now =>
          grants.modify { m =>
            m.values.find(g =>
              g.userCode == uc && g.expiresAt.isAfter(now) && g.state == DeviceState.Pending
            ) match
              case None => (m, Left("unknown or expired user code"))
              case Some(g) =>
                val g2 = g.copy(state = DeviceState.Granted(userId))
                (m.updated(g.deviceCode, g2), Right(g2))
          }
        }

object DeviceFlow:
  def inMemory(tokens: UserTokens): IO[DeviceFlow] =
    Ref.of[IO, Map[String, DeviceGrant]](Map.empty).map(new DeviceFlow(_, tokens))
