package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import neuropublish.api.AssetInventory
import neuropublish.api.Protocol.given
import neuropublish.protocol.Sha256

/** Which workspaces own which digests (ADR 0004: cross-tenant dedup must not become an existence
  * oracle). The object store is shared; an upload-session response reports a digest present only
  * when this workspace registered it — and a workspace is registered only for bytes it uploaded
  * itself and the server verified at commit — so an object another tenant stored is still "missing"
  * for this one and its upload merely rewrites identical bytes.
  */
trait WorkspaceAssets:
  def has(ws: String, d: Sha256): IO[Boolean]
  def register(ws: String, d: Sha256, size: Long): IO[Unit]
  def registerAll(ws: String, ds: List[(Sha256, Long)]): IO[Unit] =
    ds.traverse_((d, n) => register(ws, d, n))

  /** Forget a digest in every workspace (garbage collection deleted the object). */
  def unregister(d: Sha256): IO[Unit]

/** A negotiated upload, persisted so garbage collection and a restarted server see it; in
  * control-plane (local) mode the manifest bytes are kept beside it until commit.
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

trait UploadSessions:
  def get(id: String): IO[Option[UploadSession]]
  def put(s: UploadSession): IO[Unit]
  def putManifest(id: String, bytes: Array[Byte]): IO[Unit]
  def manifest(id: String): IO[Option[Array[Byte]]]
  def remove(id: String): IO[Unit]
  def list: IO[List[UploadSession]]
