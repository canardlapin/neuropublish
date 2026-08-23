package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import neuropublish.api.AssetInventory
import neuropublish.api.Protocol.given
import neuropublish.protocol.Sha256

/** Which workspaces own which digests (ADR 0004: cross-tenant dedup must not become an existence
  * oracle). The object store is shared; an upload-session response reports a digest present only
  * when this workspace registered it, so an object another tenant stored is still "missing" for
  * this one and its upload merely rewrites identical bytes. `<data>/workspace-assets/<ws>/<hex>` —
  * an empty marker per registration.
  */
final class WorkspaceAssets(dir: Path):
  private def file(ws: String, d: Sha256) = dir / ws / d.hex
  def has(ws: String, d: Sha256): IO[Boolean] =
    if !Ids.valid(ws) then IO.pure(false) else Files[IO].exists(file(ws, d))
  def register(ws: String, d: Sha256): IO[Unit] =
    val p = file(ws, d)
    Files[IO].createDirectories(p.parent.get) *>
      Files[IO].exists(p).flatMap(if _ then IO.unit
      else
        IO.blocking(java.nio.file.Files.createFile(p.toNioPath)).void.recover {
          case _: java.nio.file.FileAlreadyExistsException => ()
        })
  def registerAll(ws: String, ds: List[Sha256]): IO[Unit] = ds.traverse_(register(ws, _))

/** A negotiated upload, persisted at `<data>/upload-sessions/<id>.json` so garbage collection and a
  * restarted server see it; the manifest bytes (control-plane mode) sit beside it as
  * `<id>.manifest`.
  */
final case class UploadSession(
    id: String,
    workspace: String,
    project: String,
    manifestDigest: String,
    manifestSize: Long,
    parent: Option[String],
    inventory: List[AssetInventory],
    createdAt: String,
    manifestUploaded: Boolean = false
):
  def key: ProjectKey = ProjectKey(workspace, project)
  def digest: Sha256 = Sha256.unsafe(manifestDigest.stripPrefix("sha256:"))
  def created: Instant = Instant.parse(createdAt)
object UploadSession:
  given Codec[UploadSession] = deriveCodec

final class UploadSessions(dir: Path):
  private def file(id: String) = dir / s"$id.json"
  private def manifestFile(id: String) = dir / s"$id.manifest"
  def get(id: String): IO[Option[UploadSession]] =
    if !Ids.valid(id) then IO.none else JsonFiles.read[UploadSession](file(id))
  def put(s: UploadSession): IO[Unit] = JsonFiles.write(file(s.id), s)
  def putManifest(id: String, bytes: Array[Byte]): IO[Unit] =
    get(id).flatMap {
      case None => IO.unit
      case Some(s) =>
        Files[IO].createDirectories(dir) *>
          fs2.Stream.emits(bytes).through(Files[IO].writeAll(manifestFile(id))).compile.drain *>
          put(s.copy(manifestUploaded = true))
    }
  def manifest(id: String): IO[Option[Array[Byte]]] =
    Files[IO].exists(manifestFile(id)).flatMap {
      case false => IO.none
      case true => Files[IO].readAll(manifestFile(id)).compile.to(Array).map(Some(_))
    }
  def remove(id: String): IO[Unit] =
    Files[IO].deleteIfExists(file(id)) *> Files[IO].deleteIfExists(manifestFile(id)).void
  def list: IO[List[UploadSession]] = JsonFiles.list[UploadSession](dir)
