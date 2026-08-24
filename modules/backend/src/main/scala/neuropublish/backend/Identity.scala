package neuropublish.backend

import cats.effect.IO
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Codec
import io.circe.generic.semiauto.*
import neuropublish.api.Membership

/** The local-fs [[Identity]]: `<data>/users/<userId>.json` and
  * `<data>/users/identities/<issuer>/<sha256(subject)>.json`.
  */
object LocalIdentity:
  def apply(root: Path): IO[Identity] = Mutex[IO].map(m => new Local(root / "users", m))

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
      Identity.authenticateLocal(lookupIdentity)(email, password)

    def ensureLocalUser(email: String, name: String, password: String): IO[UserRecord] =
      mutex.lock.surround {
        lookupIdentity(Identity.LocalIssuer, email).flatMap {
          case Some(u) => IO.pure(u)
          case None =>
            for
              id <- Identity.newId
              hash <- Secrets.hashPassword(password)
              now <- IO.realTimeInstant
              u = UserRecord(id, email, name, Some(hash), now.toString)
              _ <- JsonFiles.write(userFile(id), u)
              _ <- JsonFiles.write(
                identityFile(Identity.LocalIssuer, email),
                IdentityRecord(Identity.LocalIssuer, email.toLowerCase, id)
              )
            yield u
        }
      }

    def changeLocalPassword(email: String, password: String): IO[Option[UserRecord]] =
      mutex.lock.surround {
        lookupIdentity(Identity.LocalIssuer, email).flatMap {
          case None => IO.none
          case Some(u) =>
            Secrets.hashPassword(password).flatMap { hash =>
              val changed = u.copy(password = Some(hash))
              JsonFiles.write(userFile(u.id), changed).as(Some(changed))
            }
        }
      }

/** `workspace_members` on the local fs: one file per workspace under `<data>/members/<ws>.json`. */
final class LocalMembers(dir: Path, mutex: Mutex[IO]) extends Members:
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

  def set(ws: String, userId: String, role: Role): IO[Unit] = mutex.lock.surround {
    IO.realTimeInstant.flatMap { now =>
      read(ws).flatMap { ms =>
        val rest = ms.filterNot(_.userId == userId)
        JsonFiles.write(file(ws), MembersFile(rest :+ MemberRecord(userId, role, now.toString)))
      }
    }
  }
object LocalMembers:
  def apply(root: Path): IO[Members] = Mutex[IO].map(m => new LocalMembers(root / "members", m))
