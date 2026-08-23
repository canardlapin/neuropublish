package neuropublish.backend

import cats.effect.IO
import fs2.io.file.Path
import io.circe.Codec
import io.circe.generic.semiauto.*
import neuropublish.api.CredentialSummary

/** Publisher credentials: non-human principals scoped to exactly one project (ADR 0004). The secret
  * is shown once; `<data>/credentials/<id>.json` keeps its hash and
  * `<data>/credentials/by-hash/<sha256>.json` maps a presented secret back to the id.
  */
final case class CredentialRecord(
    id: String,
    name: String,
    workspace: String,
    project: String,
    secretHash: String,
    createdAt: String,
    createdBy: String,
    revokedAt: Option[String]
):
  def key: ProjectKey = ProjectKey(workspace, project)
  def summary: CredentialSummary = CredentialSummary(id, name, project, createdAt, createdBy)
object CredentialRecord:
  given Codec[CredentialRecord] = deriveCodec

final class Credentials(dir: Path):
  private final case class HashRef(id: String)
  private given Codec[HashRef] = deriveCodec
  private def file(id: String) = dir / s"$id.json"
  private def hashFile(hash: String) = dir / "by-hash" / s"$hash.json"

  /** Returns the record and the clear secret, which is never written anywhere. */
  def create(key: ProjectKey, name: String, createdBy: String): IO[(CredentialRecord, String)] =
    for
      id <- Secrets.token(9).map(t => "c-" + t.filter(_.isLetterOrDigit).take(10))
      secret <- Secrets.token(32).map("npc_" + _)
      now <- IO.realTimeInstant
      hash = Secrets.sha256Hex(secret)
      rec = CredentialRecord(
        id,
        name,
        key.workspace,
        key.project,
        hash,
        now.toString,
        createdBy,
        None
      )
      _ <- JsonFiles.write(file(id), rec)
      _ <- JsonFiles.write(hashFile(hash), HashRef(id))
    yield (rec, secret)

  def get(id: String): IO[Option[CredentialRecord]] =
    if !Ids.valid(id) then IO.none else JsonFiles.read[CredentialRecord](file(id))

  /** The credential a presented secret names, revoked or not (the caller decides). */
  def resolve(secret: String): IO[Option[CredentialRecord]] =
    JsonFiles.read[HashRef](hashFile(Secrets.sha256Hex(secret))).flatMap {
      case None => IO.none
      case Some(h) => get(h.id)
    }

  def list(key: ProjectKey): IO[List[CredentialRecord]] =
    JsonFiles.list[CredentialRecord](dir).map(_.filter(c => c.key == key && c.revokedAt.isEmpty))

  def revoke(id: String): IO[Option[CredentialRecord]] =
    get(id).flatMap {
      case None => IO.none
      case Some(c) if c.revokedAt.isDefined => IO.pure(Some(c))
      case Some(c) =>
        IO.realTimeInstant.flatMap { now =>
          val r = c.copy(revokedAt = Some(now.toString))
          JsonFiles.write(file(id), r).as(Some(r))
        }
    }
