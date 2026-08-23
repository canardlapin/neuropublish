package neuropublish.backend

import cats.effect.IO
import cats.effect.std.Mutex
import fs2.io.file.Path
import io.circe.Json
import neuropublish.api.ViewVersion

/** Local-fs [[Views]]: `<data>/views/<viewId>.json`, one document per view with all its versions.
  */
final class LocalViews(dir: Path, mutex: Mutex[IO]) extends Views:
  private def file(id: String) = dir / s"$id.json"

  def create(rev: RevisionRecord, name: String, state: Json, owner: String): IO[ViewRecord] =
    for
      id <- Views.newId
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

  def resolveId(id: String): IO[Option[ViewRecord]] =
    if !Ids.valid(id) then IO.none else JsonFiles.read[ViewRecord](file(id))
  def get(workspace: String, id: String): IO[Option[ViewRecord]] =
    resolveId(id).map(_.filter(_.workspace == workspace))

  def update(id: String, state: Json, savedBy: String): IO[Option[ViewRecord]] =
    mutex.lock.surround {
      resolveId(id).flatMap {
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

object LocalViews:
  def apply(root: Path): IO[Views] = Mutex[IO].map(m => new LocalViews(root / "views", m))
