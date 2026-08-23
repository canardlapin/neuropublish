package neuropublish.backend

import cats.effect.IO
import io.circe.{Codec, Json}
import io.circe.generic.semiauto.*
import neuropublish.api.{SavedViewDetail, SavedViewSummary, ViewVersion}
import neuropublish.api.Stage4.given

/** Saved views: owned, named, every save an immutable new version. `state` is opaque to the server
  * (the frontend's workspace layout). A view never touches the revision or the manifest object it
  * points at.
  */
final case class ViewRecord(
    id: String,
    name: String,
    revision: String,
    workspace: String,
    project: String,
    owner: String,
    versions: List[ViewVersion]
):
  def latest: Int = versions.map(_.version).maxOption.getOrElse(0)
  def version(n: Int): Option[ViewVersion] = versions.find(_.version == n)
  def detail: SavedViewDetail =
    SavedViewDetail(id, name, revision, workspace, project, owner, latest, versions)
  def summary: SavedViewSummary =
    SavedViewSummary(
      id,
      name,
      revision,
      owner,
      latest,
      versions.lastOption.map(_.savedAt).getOrElse("")
    )
object ViewRecord:
  given Codec[ViewRecord] = deriveCodec

trait Views:
  def create(rev: RevisionRecord, name: String, state: Json, owner: String): IO[ViewRecord]
  def get(id: String): IO[Option[ViewRecord]]

  /** Append version n+1; the earlier versions are immutable and stay addressable by links. */
  def update(id: String, state: Json, savedBy: String): IO[Option[ViewRecord]]
  def listForRevision(revision: String): IO[List[ViewRecord]]

object Views:
  def newId: IO[String] = Secrets.token(9).map(t => "v-" + t.filter(_.isLetterOrDigit).take(10))
