package neuropublish.backend

import cats.effect.IO
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
  * small boundary"). The alpha provider is `local`: email + password, PBKDF2 hashes at rest.
  */
trait Identity:
  def authenticate(email: String, password: String): IO[Option[UserRecord]]
  def lookup(userId: String): IO[Option[UserRecord]]
  def lookupIdentity(issuer: String, subject: String): IO[Option[UserRecord]]

  /** Create the user with a local password if no identity (issuer, subject) exists; returns it. */
  def ensureLocalUser(email: String, name: String, password: String): IO[UserRecord]

  /** Replace one existing local identity's password; external identities and unknown emails are
    * absent. The operator boundary revokes that user's sessions and tokens after this succeeds.
    */
  def changeLocalPassword(email: String, password: String): IO[Option[UserRecord]]

object Identity:
  val LocalIssuer = "local"

  /** Generated user ids: `u-` plus ten url-safe characters. */
  def newId: IO[String] = Secrets.token(9).map(t => "u-" + t.filter(_.isLetterOrDigit).take(10))

  /** Local-provider authentication over any lookup: an unknown email still costs one hash so timing
    * does not reveal existence.
    */
  def authenticateLocal(
      lookupIdentity: (String, String) => IO[Option[UserRecord]]
  )(email: String, password: String): IO[Option[UserRecord]] =
    lookupIdentity(LocalIssuer, email).flatMap {
      case Some(u) if u.password.isDefined =>
        Secrets.verifyPassword(password, u.password.get).map(ok => Option.when(ok)(u))
      case _ => Secrets.hashPassword(password).as(None)
    }

/** `workspace_members`: who belongs to a workspace and with which role. */
trait Members:
  def role(ws: String, userId: String): IO[Option[Role]]
  def members(ws: String): IO[List[MemberRecord]]
  def membershipsOf(userId: String): IO[List[Membership]]

  /** Add or replace the member's role. */
  def set(ws: String, userId: String, role: Role): IO[Unit]
