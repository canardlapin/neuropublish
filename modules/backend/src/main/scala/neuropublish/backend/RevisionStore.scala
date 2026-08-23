package neuropublish.backend

import cats.effect.IO
import cats.effect.std.Mutex
import fs2.io.file.{Files, Path}
import io.circe.Codec
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import neuropublish.protocol.Sha256

/** The local-fs [[RevisionStore]]: `<data>/projects/<ws>/<project>.json` holds the head and the
  * history, `<data>/revisions/<id>.json` one record per revision. One mutex serializes commits.
  */
object LocalRevisionStore:
  private final case class ProjectFile(head: Option[String], revisions: List[RevisionRecord])
  private given Codec[ProjectFile] = deriveCodec

  def apply(root: Path): IO[RevisionStore] =
    Mutex[IO].map(m => new LocalFs(root, m))

  final class LocalFs(root: Path, mutex: Mutex[IO]) extends RevisionStore:
    private def file(k: ProjectKey) = root / "projects" / k.workspace / s"${k.project}.json"
    private def revFile(id: String) = root / "revisions" / s"$id.json"
    private def read(k: ProjectKey): IO[Option[ProjectFile]] =
      Files[IO].exists(file(k)).flatMap {
        case false => IO.none
        case true => Files[IO].readUtf8(file(k)).compile.string.flatMap(s =>
            IO.fromEither(_root_.io.circe.parser.decode[ProjectFile](s)).map(Some(_))
          )
      }

    /** Write via a unique temp file and atomic move so a crash never leaves invalid JSON. */
    private def atomicWrite(target: Path, text: String): IO[Unit] =
      val tmp = target.parent.get /
        s"${target.fileName}.${java.util.UUID.randomUUID().toString.take(8)}.part"
      Files[IO].createDirectories(target.parent.get) *>
        fs2.Stream.emit(text).through(Files[IO].writeUtf8(tmp)).compile.drain *>
        Files[IO].move(tmp, target, fs2.io.file.CopyFlags(fs2.io.file.CopyFlag.ReplaceExisting))
    private def write(k: ProjectKey, p: ProjectFile): IO[Unit] =
      atomicWrite(file(k), p.asJson.spaces2)

    def createProject(key: ProjectKey): IO[Unit] = mutex.lock.surround {
      read(key).flatMap(p => if p.isDefined then IO.unit else write(key, ProjectFile(None, Nil)))
    }
    def projectExists(key: ProjectKey): IO[Boolean] = Files[IO].exists(file(key))
    def head(key: ProjectKey): IO[Option[String]] =
      mutex.lock.surround(read(key).map(_.flatMap(_.head)))
    def revisions(key: ProjectKey): IO[List[RevisionRecord]] =
      mutex.lock.surround(read(key).map(_.map(_.revisions).getOrElse(Nil)))
    def revision(id: String): IO[Option[RevisionRecord]] =
      Files[IO].exists(revFile(id)).flatMap {
        case false => IO.none
        case true => Files[IO].readUtf8(revFile(id)).compile.string.flatMap(s =>
            IO.fromEither(_root_.io.circe.parser.decode[RevisionRecord](s)).map(Some(_))
          )
      }
    def commit(
        key: ProjectKey,
        parent: Option[String],
        manifestDigest: Sha256,
        message: Option[String],
        committedAt: String
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
            Files[IO].exists(revFile(id)).flatMap {
              case true => IO.raiseError(IllegalStateException(s"revision id collision: $id"))
              case false =>
                atomicWrite(revFile(id), rec.asJson.spaces2) *>
                  write(key, ProjectFile(Some(id), p.revisions :+ rec)).as(Right(rec))
            }
        }
      }
