package neuropublish.backend

import cats.effect.IO
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import scala.concurrent.duration.*

/** Browser sessions: the cookie carries a random secret; only its SHA-256 is stored with the user
  * id and expiry.
  */
final case class SessionRecord(userId: String, createdAt: String, expiresAt: String)
object SessionRecord:
  given Codec[SessionRecord] = deriveCodec

trait Sessions:
  def lifetime: FiniteDuration = Sessions.Lifetime

  /** Returns the clear secret (for the cookie) — never stored. */
  def create(userId: String): IO[(String, Instant)]
  def resolve(secret: String): IO[Option[String]]
  def revoke(secret: String): IO[Unit]

  /** Revoke every browser session of one user; returns how many. */
  def revokeAll(userId: String): IO[Int]

object Sessions:
  val Lifetime: FiniteDuration = 24.hours

/** User tokens minted by the device flow. A token lives [[UserTokens.Lifetime]] from minting
  * (`expiresAt`; a record without one predates expiry and is treated as expired) and can be revoked
  * one at a time (logout) or all at once per user.
  */
final case class UserTokenRecord(
    userId: String,
    client: String,
    createdAt: String,
    expiresAt: Option[String]
)
object UserTokenRecord:
  given Codec[UserTokenRecord] = deriveCodec

trait UserTokens:
  def lifetime: FiniteDuration = UserTokens.Lifetime
  def mint(userId: String, client: String): IO[String]

  /** The live token a secret names; an expired one is answered as unknown. */
  def resolve(secret: String): IO[Option[UserTokenRecord]]
  def revoke(secret: String): IO[Unit]

  /** Revoke every token of one user; returns how many. */
  def revokeAll(userId: String): IO[Int]

object UserTokens:
  val Lifetime: FiniteDuration = 30.days
  val Prefix = "npu_"
