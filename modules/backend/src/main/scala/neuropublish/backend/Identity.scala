package neuropublish.backend

import cats.effect.IO
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Codec
import io.circe.generic.semiauto.*
import neuropublish.api.{Membership, User}

/** Internal user row plus its external `identities(issuer, subject)` (ADR 0004). The password hash
  * exists only for the local alpha provider; an external IdP adds identities, not users.
  */
final case class UserRecord(
    id: String,
    email: String,
    name: String,
    password: Option[Secrets.PasswordHash],
    createdAt: String
):
  def public: User = User(id, email, name)
object UserRecord:
  given Codec[UserRecord] = deriveCodec

final case class IdentityRecord(issuer: String, subject: String, userId: String)
object IdentityRecord:
  given Codec[IdentityRecord] = deriveCodec

enum Role(val render: String):
  case Owner extends Role("owner")
  case Admin extends Role("admin")
  case Member extends Role("member")
  case Viewer extends Role("viewer")
  def isAdmin: Boolean = this == Owner || this == Admin
  def canPublish: Boolean = this != Viewer

  /** Saving views and minting share links: owner, admin, member — a viewer only looks. */
  def canShare: Boolean = this != Viewer
object Role:
  def parse(s: String): Option[Role] = values.find(_.render == s)
  given Codec[Role] = Codec.from(
    io.circe.Decoder[String].emap(s => parse(s).toRight(s"unknown role $s")),
    io.circe.Encoder[String].contramap(_.render)
  )

final case class MemberRecord(userId: String, role: Role, addedAt: String)
object MemberRecord:
  given Codec[MemberRecord] = deriveCodec

/** The identity boundary (architecture: "Identity provider choice is a deployment decision behind a
  * small boundary"). `Local` is the alpha provider: email + password, PBKDF2 hashes on disk.
  */
trait Identity:
  def authenticate(email: String, password: String): IO[Option[UserRecord]]
  def lookup(userId: String): IO[Option[UserRecord]]
  def lookupIdentity(issuer: String, subject: String): IO[Option[UserRecord]]

  /** Create the user with a local password if no identity (issuer, subject) exists; returns it. */
  def ensureLocalUser(email: String, name: String, password: String): IO[UserRecord]

object Identity:
  val LocalIssuer = "local"

  def local(root: Path): IO[Identity] = Mutex[IO].map(m => new Local(root / "users", m))

  /** `<data>/users/<userId>.json`; `<data>/users/identities/<issuer>/<sha256(subject)>.json`. */
  final class Local(dir: Path, mutex: Mutex[IO]) extends Identity:
    private def userFile(id: String) = dir / s"$id.json"
    private def identityFile(issuer: String, subject: String) =
      dir / "identities" / issuer / s"${Secrets.sha256Hex(subject.toLowerCase)}.json"

    def lookup(userId: String): IO[Option[UserRecord]] =
      if !Ids.valid(userId) then IO.none else JsonFiles.read[UserRecord](userFile(userId))

    def lookupIdentity(issuer: String, subject: String): IO[Option[UserRecord]] =
      if !Ids.valid(issuer) then IO.none
      else
        JsonFiles.read[IdentityRecord](identityFile(issuer, subject)).flatMap {
          case None => IO.none
          case Some(i) => lookup(i.userId)
        }

    def authenticate(email: String, password: String): IO[Option[UserRecord]] =
      lookupIdentity(LocalIssuer, email).flatMap {
        case Some(u) if u.password.isDefined =>
          Secrets.verifyPassword(password, u.password.get).map(ok => Option.when(ok)(u))
        case _ =>
          // constant-ish time for unknown users: still run a hash so timing does not reveal existence
          Secrets.hashPassword(password).as(None)
      }

    def ensureLocalUser(email: String, name: String, password: String): IO[UserRecord] =
      mutex.lock.surround {
        lookupIdentity(LocalIssuer, email).flatMap {
          case Some(u) => IO.pure(u)
          case None =>
            for
              id <- Secrets.token(9).map(t => "u-" + t.filter(_.isLetterOrDigit).take(10))
              hash <- Secrets.hashPassword(password)
              now <- IO.realTimeInstant
              u = UserRecord(id, email, name, Some(hash), now.toString)
              _ <- JsonFiles.write(userFile(id), u)
              _ <- JsonFiles.write(
                identityFile(LocalIssuer, email),
                IdentityRecord(LocalIssuer, email.toLowerCase, id)
              )
            yield u
        }
      }

/** `workspace_members`: one file per workspace under `<data>/members/<workspace>.json`. */
final class Members(dir: Path, mutex: Mutex[IO]):
  private final case class MembersFile(members: List[MemberRecord])
  private given Codec[MembersFile] = deriveCodec
  private def file(ws: String) = dir / s"$ws.json"
  private def read(ws: String): IO[List[MemberRecord]] =
    if !Ids.valid(ws) then IO.pure(Nil)
    else JsonFiles.read[MembersFile](file(ws)).map(_.map(_.members).getOrElse(Nil))

  def role(ws: String, userId: String): IO[Option[Role]] =
    read(ws).map(_.find(_.userId == userId).map(_.role))
  def members(ws: String): IO[List[MemberRecord]] = read(ws)
  def membershipsOf(userId: String): IO[List[Membership]] =
    fs2.io.file.Files[IO].exists(dir).flatMap {
      case false => IO.pure(Nil)
      case true =>
        fs2.io.file.Files[IO].list(dir).filter(_.fileName.toString.endsWith(".json")).compile.toList
          .flatMap(_.traverse { p =>
            val ws = p.fileName.toString.stripSuffix(".json")
            role(ws, userId).map(_.map(r => Membership(ws, r.render)))
          }.map(_.flatten.sortBy(_.workspace)))
    }

  /** Add or replace the member's role. */
  def set(ws: String, userId: String, role: Role): IO[Unit] = mutex.lock.surround {
    IO.realTimeInstant.flatMap { now =>
      read(ws).flatMap { ms =>
        val rest = ms.filterNot(_.userId == userId)
        JsonFiles.write(file(ws), MembersFile(rest :+ MemberRecord(userId, role, now.toString)))
      }
    }
  }
object Members:
  def localFs(root: Path): IO[Members] = Mutex[IO].map(m => new Members(root / "members", m))
