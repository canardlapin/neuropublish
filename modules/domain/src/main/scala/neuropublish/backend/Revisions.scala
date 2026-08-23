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
)
object RevisionRecord:
  given Codec[RevisionRecord] = deriveCodec

/** Stale-parent rejection carries the current head so the publisher can re-push. */
final case class StaleParent(head: Option[String])

/** Projects and their linear revision history. Every row is workspace-scoped (ADR 0004). The
  * local-fs implementation lives in the backend module; the PostgreSQL one in `persistence`.
  */
trait RevisionStore:
  def createProject(key: ProjectKey): IO[Unit]
  def projectExists(key: ProjectKey): IO[Boolean]
  def head(key: ProjectKey): IO[Option[String]]
  def revisions(key: ProjectKey): IO[List[RevisionRecord]]
  def revision(id: String): IO[Option[RevisionRecord]]

  /** Append iff `parent` equals the current head; atomic per project. */
  def commit(
      key: ProjectKey,
      parent: Option[String],
      manifestDigest: Sha256,
      message: Option[String],
      committedAt: String
  ): IO[Either[StaleParent, RevisionRecord]]

  /** Project the committed manifest into the searchable read model (`analyses`, `result_fields`,
    * `revision_assets`, …). A no-op for stores without a read model; `reindex` rebuilds the same
    * rows from the stored manifest bytes, so a failure here never loses scientific content.
    */
  def index(revision: RevisionRecord, manifest: Manifest): IO[Unit] =
    val _ = (revision, manifest)
    IO.unit

object RevisionId:
  /** Short, stable, content-derived: first 12 hex of sha256(project, ordinal, manifest digest). */
  def of(key: ProjectKey, ordinal: Int, manifestDigest: Sha256): String =
    Sha256.of(s"${key.render}\n$ordinal\n${manifestDigest.hex}".getBytes("UTF-8")).hex.take(12)
