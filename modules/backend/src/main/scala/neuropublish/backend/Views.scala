package neuropublish.backend

import cats.effect.IO
import cats.effect.std.Mutex
import fs2.io.file.Path
import io.circe.{Codec, Json}
import io.circe.generic.semiauto.*
import neuropublish.api.{SavedViewDetail, SavedViewSummary, ViewVersion}
import neuropublish.api.Stage4.given

/** Saved views: owned, named, every save an immutable new version. `state` is opaque to the server
  * (the frontend's workspace layout). Stored under `<data>/views/<viewId>.json`; a view never
  * touches the revision or the manifest object it points at.
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

final class Views(dir: Path, mutex: Mutex[IO]):
  private def file(id: String) = dir / s"$id.json"

  def create(
      rev: RevisionRecord,
      name: String,
      state: Json,
      owner: String
  ): IO[ViewRecord] =
    for
      id <- Secrets.token(9).map(t => "v-" + t.filter(_.isLetterOrDigit).take(10))
      now <- IO.realTimeInstant
      rec = ViewRecord(
        id,
        name,
        rev.id,
        rev.workspace,
        rev.project,
        owner,
        List(ViewVersion(1, state, now.toString, owner))
      )
      _ <- JsonFiles.write(file(id), rec)
    yield rec

  def get(id: String): IO[Option[ViewRecord]] =
    if !Ids.valid(id) then IO.none else JsonFiles.read[ViewRecord](file(id))

  /** Append version n+1; the earlier versions are immutable and stay addressable by links. */
  def update(id: String, state: Json, savedBy: String): IO[Option[ViewRecord]] =
    mutex.lock.surround {
      get(id).flatMap {
        case None => IO.none
        case Some(v) =>
          IO.realTimeInstant.flatMap { now =>
            val next = v.copy(versions =
              v.versions :+ ViewVersion(v.latest + 1, state, now.toString, savedBy)
            )
            JsonFiles.write(file(id), next).as(Some(next))
          }
      }
    }

  def listForRevision(revision: String): IO[List[ViewRecord]] =
    JsonFiles.list[ViewRecord](dir).map(_.filter(_.revision == revision))

object Views:
  def localFs(root: Path): IO[Views] = Mutex[IO].map(m => new Views(root / "views", m))
