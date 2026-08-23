package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import neuropublish.protocol.Sha256

/** Local-fs [[WorkspaceAssets]]: `<data>/workspace-assets/<ws>/<hex>`, an empty marker per
  * registration.
  */
final class LocalWorkspaceAssets(dir: Path) extends WorkspaceAssets:
  private def file(ws: String, d: Sha256) = dir / ws / d.hex
  def has(ws: String, d: Sha256): IO[Boolean] =
    if !Ids.valid(ws) then IO.pure(false) else Files[IO].exists(file(ws, d))
  def register(ws: String, d: Sha256, size: Long): IO[Unit] =
    val p = file(ws, d)
    Files[IO].createDirectories(p.parent.get) *>
      IO.blocking(java.nio.file.Files.createFile(p.toNioPath)).void.recover {
        case _: java.nio.file.FileAlreadyExistsException => ()
      }
  def unregister(d: Sha256): IO[Unit] =
    Files[IO].list(dir).evalMap(ws => Files[IO].deleteIfExists(ws / d.hex)).compile.drain
      .recover { case _: java.nio.file.NoSuchFileException => () }

/** Local-fs [[UploadSessions]]: `<data>/upload-sessions/<id>.json`, the manifest bytes
  * (control-plane mode) beside it as `<id>.manifest`.
  */
final class LocalUploadSessions(dir: Path) extends UploadSessions:
  private def file(id: String) = dir / s"$id.json"
  private def manifestFile(id: String) = dir / s"$id.manifest"
  def get(id: String): IO[Option[UploadSession]] =
    if !Ids.valid(id) then IO.none else JsonFiles.read[UploadSession](file(id))
  def put(s: UploadSession): IO[Unit] = JsonFiles.write(file(s.id), s)
  def putManifest(id: String, bytes: Array[Byte]): IO[Unit] =
    get(id).flatMap {
      case None => IO.unit
      case Some(s) => JsonFiles.writeBytes(manifestFile(id), bytes) *>
          put(s.copy(manifestUploaded = true))
    }
  def manifest(id: String): IO[Option[Array[Byte]]] =
    if !Ids.valid(id) then IO.none else JsonFiles.readBytes(manifestFile(id))
  def remove(id: String): IO[Unit] =
    Files[IO].deleteIfExists(file(id)) *> Files[IO].deleteIfExists(manifestFile(id)).void
  def list: IO[List[UploadSession]] = JsonFiles.list[UploadSession](dir)
