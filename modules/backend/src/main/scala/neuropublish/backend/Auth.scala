package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import neuropublish.api.ApiError

/** Who is asking. Every request resolves to exactly one principal before any handler runs. */
enum Principal:
  /** Browser session (`np_session` cookie); `secret` is kept only so logout can revoke it. */
  case Session(user: UserRecord, secret: String)

  /** Bearer user token minted by the device flow (`npub login`); `secret` only so logout can revoke
    * it.
    */
  case UserToken(user: UserRecord, secret: String)

  /** Project-scoped publisher credential: a non-human principal (ADR 0004). */
  case Credential(credential: CredentialRecord)

  /** Deprecated static NP_TOKEN: publish and read anywhere; no identity. Removed with Stage 5. */
  case Legacy
  case Anonymous

  def signedIn: Option[UserRecord] = this match
    case Session(u, _) => Some(u)
    case UserToken(u, _) => Some(u)
    case _ => None

  /** Actor string for audit rows. */
  def actor: String = this match
    case Session(u, _) => s"user:${u.id}"
    case UserToken(u, _) => s"user:${u.id}"
    case Credential(c) => s"credential:${c.id}"
    case Legacy => "legacy-token"
    case Anonymous => "anonymous"

/** Principal resolution and the authorization rules, in one place so tests can read them:
  *
  *   - publishing (upload session, object, manifest, commit) needs a workspace member who is not a
  *     viewer, or a credential scoped to exactly that project (another project → 403);
  *   - reading a project, revision, rendition, view, or provenance needs a member (session or user
  *     token) or that project's credential; share routes authorize by link secret instead;
  *   - credential and member management and the audit log need an owner or admin;
  *   - saved views and share links are created by members who are not viewers ([[Role.canShare]]);
  *     updates and revocations by the owner or creator, or a workspace admin.
  *
  * No principal → 401 `unauthorized`; a principal the rule rejects → 403 `forbidden` — except that
  * a non-member asking about a *specific* record (revision, view, link, credential) gets the same
  * 404 as for a record that does not exist, so membership never becomes an existence oracle
  * ([[Routes]]). Bearer tokens that are unknown, expired, or revoked are all one generic 401.
  */
final class Authz(
    identity: Identity,
    members: Members,
    sessions: Sessions,
    userTokens: UserTokens,
    credentials: Credentials,
    legacyToken: Option[String]
):
  private def unauthorized(msg: String) = ApiError("unauthorized", msg)
  private def forbidden(msg: String) = ApiError("forbidden", msg)

  def resolve(bearer: Option[String], cookie: Option[String]): IO[Either[ApiError, Principal]] =
    bearer.map(_.trim).filter(_.nonEmpty) match
      case Some(t) => resolveBearer(t)
      case None =>
        cookie.filter(_.nonEmpty) match
          case None => IO.pure(Right(Principal.Anonymous))
          case Some(c) =>
            sessions.resolve(c).flatMap {
              case None =>
                IO.pure(Right(Principal.Anonymous)) // expired or unknown: just no session
              case Some(uid) =>
                identity.lookup(uid).map(_.fold(Right(Principal.Anonymous))(u =>
                  Right(Principal.Session(u, c))
                ))
            }

  private def resolveBearer(t: String): IO[Either[ApiError, Principal]] =
    if legacyToken.contains(t) then IO.pure(Right(Principal.Legacy))
    else
      userTokens.resolve(t).flatMap {
        case Some(tok) =>
          identity.lookup(tok.userId).map(
            _.map(Principal.UserToken(_, t)).toRight(unauthorized("invalid token"))
          )
        case None =>
          credentials.resolve(t).map {
            case Some(c) if c.revokedAt.isEmpty => Right(Principal.Credential(c))
            case _ => Left(unauthorized("invalid token")) // unknown and revoked are the same
          }
      }

  def role(p: Principal, workspace: String): IO[Option[Role]] =
    p.signedIn.fold(IO.none)(u => members.role(workspace, u.id))

  def requireUser(p: Principal): Either[ApiError, UserRecord] = p match
    case Principal.Anonymous => Left(unauthorized("sign in required"))
    case other => other.signedIn.toRight(forbidden("a signed-in user is required here"))

  def requireMember(p: Principal, workspace: String): IO[Either[ApiError, (UserRecord, Role)]] =
    requireUser(p) match
      case Left(e) => IO.pure(Left(e))
      case Right(u) =>
        members.role(workspace, u.id).map(
          _.map(u -> _).toRight(forbidden(s"not a member of workspace $workspace"))
        )

  /** A member who may save views and mint share links (viewers cannot). */
  def requireSharer(p: Principal, workspace: String): IO[Either[ApiError, (UserRecord, Role)]] =
    requireMember(p, workspace).map(_.flatMap((u, r) =>
      if r.canShare then Right((u, r)) else Left(forbidden("viewers cannot save or share views"))
    ))

  def requireAdmin(p: Principal, workspace: String): IO[Either[ApiError, UserRecord]] =
    requireMember(p, workspace).map(_.flatMap((u, r) =>
      if r.isAdmin then Right(u) else Left(forbidden("owner or admin role required"))
    ))

  def canRead(p: Principal, key: ProjectKey): IO[Either[ApiError, Unit]] = p match
    case Principal.Anonymous => IO.pure(Left(unauthorized("sign in required")))
    case Principal.Legacy => IO.pure(Right(()))
    case Principal.Credential(c) =>
      IO.pure(if c.key == key then Right(())
      else Left(forbidden(s"credential is scoped to ${c.key.render}")))
    case _ => requireMember(p, key.workspace).map(_.void)

  def canPublish(p: Principal, key: ProjectKey): IO[Either[ApiError, Unit]] = p match
    case Principal.Anonymous =>
      IO.pure(Left(unauthorized("sign in or a publisher credential required")))
    case Principal.Legacy => IO.pure(Right(()))
    case Principal.Credential(c) =>
      IO.pure(if c.key == key then Right(())
      else Left(forbidden(s"credential is scoped to ${c.key.render}")))
    case _ =>
      requireMember(p, key.workspace).map(_.flatMap((_, r) =>
        if r.canPublish then Right(()) else Left(forbidden("viewers cannot publish"))
      ))
