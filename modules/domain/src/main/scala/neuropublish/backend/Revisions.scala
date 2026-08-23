package neuropublish.backend

import cats.effect.IO
import io.circe.Codec
import io.circe.generic.semiauto.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest

final case class ProjectKey(workspace: String, project: String):
  def render = s"$workspace/$project"

final case class RevisionRecord(
    id: String,
    workspace: String,
    project: String,
    parent: Option[String],
    manifestDigest: String,
    message: Option[String],
    committedAt: String
):
  def key: ProjectKey = ProjectKey(workspace, project)
object RevisionRecord:
  given Codec[RevisionRecord] = deriveCodec

/** Stale-parent rejection carries the current head so the publisher can re-push. */
final case class StaleParent(head: Option[String])

/** Stored bytes that no longer match the digest the record names them by (a tampered or overwritten
  * object). Never used silently: reads fail with this, and the control plane answers 503
  * `integrity`.
  */
final case class IntegrityError(message: String) extends RuntimeException(message)

/** Projects and their linear revision history. Every row is workspace-scoped (ADR 0004). The
  * local-fs implementation lives in the backend module; the PostgreSQL one in `persistence`.
  */
trait RevisionStore:
  def createProject(key: ProjectKey): IO[Unit]
  def projectExists(key: ProjectKey): IO[Boolean]
  def head(key: ProjectKey): IO[Option[String]]
  def revisions(key: ProjectKey): IO[List[RevisionRecord]]

  /** The revision `id` of `workspace`, or None when it belongs to another workspace. */
  def revision(workspace: String, id: String): IO[Option[RevisionRecord]]

  /** Unscoped resolution of a bare id — only for the API routes that address a revision by id alone
    * (`/revisions/{id}`); the caller authorizes on the record's workspace before using it.
    */
  def resolveId(id: String): IO[Option[RevisionRecord]]

  /** Every revision of every project (garbage collection needs the complete reference set). */
  def all: IO[List[RevisionRecord]]

  /** Append iff `parent` equals the current head; atomic per project. The manifest is projected
    * into the read model (`analyses`, `result_fields`, `revision_assets`, …) and — when `enqueue`
    * is given — an ingestion job is recorded, both inside the same critical section as the head
    * update (one transaction in PostgreSQL, the commit mutex locally), so a committed revision
    * never exists without its job, nor a job without its revision.
    */
  def commit(
      key: ProjectKey,
      parent: Option[String],
      manifestDigest: Sha256,
      message: Option[String],
      committedAt: String,
      manifest: Manifest,
      enqueue: Option[IngestionQueue]
  ): IO[Either[StaleParent, RevisionRecord]]

  /** Re-project a stored manifest into the read model (a no-op for stores without one). */
  def index(revision: RevisionRecord, manifest: Manifest): IO[Unit] =
    val _ = (revision, manifest)
    IO.unit

object RevisionId:
  /** Short, stable, content-derived: first 12 hex of sha256(project, ordinal, manifest digest). */
  def of(key: ProjectKey, ordinal: Int, manifestDigest: Sha256): String =
    Sha256.of(s"${key.render}\n$ordinal\n${manifestDigest.hex}".getBytes("UTF-8")).hex.take(12)
