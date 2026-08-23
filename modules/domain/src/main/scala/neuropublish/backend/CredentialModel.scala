package neuropublish.backend

import cats.effect.IO
import io.circe.Codec
import io.circe.generic.semiauto.*
import neuropublish.api.CredentialSummary

/** Publisher credentials: non-human principals scoped to exactly one project (ADR 0004). The secret
  * is shown once; only its SHA-256 is kept.
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

trait Credentials:
  /** Returns the record and the clear secret, which is never written anywhere. */
  def create(key: ProjectKey, name: String, createdBy: String): IO[(CredentialRecord, String)]

  /** The credential `id` of `workspace`, or None when it belongs to another workspace. */
  def get(workspace: String, id: String): IO[Option[CredentialRecord]]

  /** The credential a presented secret names, revoked or not (the caller decides). Global by
    * nature: a bearer secret arrives before any workspace is known.
    */
  def resolveSecret(secret: String): IO[Option[CredentialRecord]]

  /** Live (unrevoked) credentials of one project. */
  def list(key: ProjectKey): IO[List[CredentialRecord]]
  def revoke(id: String): IO[Option[CredentialRecord]]

object Credentials:
  val Prefix = "npc_"
  def newId: IO[String] = Secrets.token(9).map(t => "c-" + t.filter(_.isLetterOrDigit).take(10))
  def newSecret: IO[String] = Secrets.token(32).map(Prefix + _)
