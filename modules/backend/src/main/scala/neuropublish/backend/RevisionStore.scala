package neuropublish.backend

import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import neuropublish.protocol.Sha256

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

/** Projects and their linear revision history. Every row is workspace-scoped (ADR 0004). Stage 2
  * replaces the file implementation with PostgreSQL behind the same algebra.
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

object RevisionStore:
  private final case class ProjectFile(head: Option[String], revisions: List[RevisionRecord])
  private given Codec[ProjectFile] = deriveCodec

  def localFs(root: Path): IO[RevisionStore] =
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
    private def write(k: ProjectKey, p: ProjectFile): IO[Unit] =
      Files[IO].createDirectories(file(k).parent.get) *>
        fs2.Stream.emit(p.asJson.spaces2).through(Files[IO].writeUtf8(file(k))).compile.drain

    def createProject(key: ProjectKey): IO[Unit] = mutex.lock.surround {
      read(key).flatMap(p => if p.isDefined then IO.unit else write(key, ProjectFile(None, Nil)))
    }
    def projectExists(key: ProjectKey): IO[Boolean] = Files[IO].exists(file(key))
    def head(key: ProjectKey): IO[Option[String]] = read(key).map(_.flatMap(_.head))
    def revisions(key: ProjectKey): IO[List[RevisionRecord]] =
      read(key).map(_.map(_.revisions).getOrElse(Nil))
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
            Files[IO].createDirectories(revFile(id).parent.get) *>
              fs2.Stream.emit(
                rec.asJson.spaces2
              ).through(Files[IO].writeUtf8(revFile(id))).compile.drain *>
              write(key, ProjectFile(Some(id), p.revisions :+ rec)).as(Right(rec))
        }
      }

object RevisionId:
  /** Short, stable, content-derived: first 7 hex of sha256(project, ordinal, manifest digest). */
  def of(key: ProjectKey, ordinal: Int, manifestDigest: Sha256): String =
    Sha256.of(s"${key.render}\n$ordinal\n${manifestDigest.hex}".getBytes("UTF-8")).hex.take(12)
