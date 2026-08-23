package neuropublish.backend

import cats.effect.IO
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Codec
import io.circe.generic.semiauto.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest

/** The local-fs [[RevisionStore]]: `<data>/projects/<ws>/<project>.json` holds the head and the
  * history, `<data>/revisions/<id>.json` one record per revision. One mutex serializes commits; the
  * ingestion job is written inside it, after the revision and the head.
  */
object LocalRevisionStore:
  private final case class ProjectFile(head: Option[String], revisions: List[RevisionRecord])
  private given Codec[ProjectFile] = deriveCodec

  def apply(root: Path): IO[RevisionStore] =
    Mutex[IO].map(m => new LocalFs(root, m))

  final class LocalFs(root: Path, mutex: Mutex[IO]) extends RevisionStore:
    private def file(k: ProjectKey) = root / "projects" / k.workspace / s"${k.project}.json"
    private def revFile(id: String) = root / "revisions" / s"$id.json"
    private def read(k: ProjectKey): IO[Option[ProjectFile]] = JsonFiles.read[ProjectFile](file(k))
    private def write(k: ProjectKey, p: ProjectFile): IO[Unit] = JsonFiles.write(file(k), p)

    def createProject(key: ProjectKey): IO[Unit] = mutex.lock.surround {
      read(key).flatMap(p => if p.isDefined then IO.unit else write(key, ProjectFile(None, Nil)))
    }
    def projectExists(key: ProjectKey): IO[Boolean] = read(key).map(_.isDefined)
    def head(key: ProjectKey): IO[Option[String]] =
      mutex.lock.surround(read(key).map(_.flatMap(_.head)))
    def revisions(key: ProjectKey): IO[List[RevisionRecord]] =
      mutex.lock.surround(read(key).map(_.map(_.revisions).getOrElse(Nil)))
    def resolveId(id: String): IO[Option[RevisionRecord]] =
      if !Ids.valid(id) then IO.none else JsonFiles.read[RevisionRecord](revFile(id))
    def revision(workspace: String, id: String): IO[Option[RevisionRecord]] =
      resolveId(id).map(_.filter(_.workspace == workspace))
    def all: IO[List[RevisionRecord]] = JsonFiles.list[RevisionRecord](root / "revisions")

    def commit(
        key: ProjectKey,
        parent: Option[String],
        manifestDigest: Sha256,
        message: Option[String],
        committedAt: String,
        manifest: Manifest,
        enqueue: Option[IngestionQueue]
    ): IO[Either[StaleParent, RevisionRecord]] =
      mutex.lock.surround {
        read(key).flatMap {
          case None => IO.raiseError(IllegalStateException(s"project ${key.render} does not exist"))
          case Some(p) if p.head != parent => IO.pure(Left(StaleParent(p.head)))
          case Some(p) =>
            val id = RevisionId.of(key, p.revisions.length, manifestDigest)
            val rec = RevisionRecord(
              id,
              key.workspace,
              key.project,
              parent,
              manifestDigest.render,
              message,
              committedAt
            )
            resolveId(id).flatMap {
              case Some(_) => IO.raiseError(IllegalStateException(s"revision id collision: $id"))
              case None =>
                JsonFiles.write(revFile(id), rec) *>
                  write(key, ProjectFile(Some(id), p.revisions :+ rec)) *>
                  enqueue.traverse_(_.enqueue(key.workspace, id)).as(Right(rec))
            }
        }
      }
