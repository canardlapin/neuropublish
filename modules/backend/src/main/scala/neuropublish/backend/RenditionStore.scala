package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import scala.concurrent.duration.*

/** Where derived renditions live (plan decision 2: outside the manifest digest). Local mode keeps
  * `<data>/renditions/<rev>/<asset>.json|.f32` and the control plane streams them; S3 mode keeps
  * `renditions/<rev>/<asset>.json|.f32` in the bucket and hands out presigned GETs, so the control
  * plane never streams rendition bytes.
  */
trait RenditionStore:
  def write(revisionId: String, assetId: String, header: String, payload: Array[Byte]): IO[Unit]
  def ready(revisionId: String, assetId: String): IO[Boolean]
  def header(revisionId: String, assetId: String): IO[Option[Array[Byte]]]
  def payload(revisionId: String, assetId: String): IO[Option[Array[Byte]]]
  def delete(revisionId: String): IO[Unit]

  /** Revision ids that have any rendition stored (garbage collection). */
  def revisions: fs2.Stream[IO, String]

  /** Signed GET URLs `(header, payload)` when the store serves bytes directly. */
  def signedUrls(
      revisionId: String,
      assetId: String,
      ttl: FiniteDuration
  ): IO[Option[(String, String)]]

object RenditionStore:
  val SignedTtl: FiniteDuration = 15.minutes

  final class LocalFs(root: Path) extends RenditionStore:
    private def dir(rev: String) = root / "renditions" / rev
    def headerPath(rev: String, asset: String): Path = dir(rev) / s"$asset.json"
    def payloadPath(rev: String, asset: String): Path = dir(rev) / s"$asset.f32"
    def write(rev: String, asset: String, header: String, payload: Array[Byte]): IO[Unit] =
      // payload last: `ready` keys on it, so a reader never sees a header without bytes
      JsonFiles.writeBytes(headerPath(rev, asset), header.getBytes("UTF-8")) *>
        JsonFiles.writeBytes(payloadPath(rev, asset), payload)
    def ready(rev: String, asset: String): IO[Boolean] = Files[IO].exists(payloadPath(rev, asset))
    def header(rev: String, asset: String): IO[Option[Array[Byte]]] =
      JsonFiles.readBytes(headerPath(rev, asset))
    def payload(rev: String, asset: String): IO[Option[Array[Byte]]] =
      JsonFiles.readBytes(payloadPath(rev, asset))
    def delete(rev: String): IO[Unit] =
      Files[IO].deleteRecursively(dir(rev)).recover {
        case _: java.nio.file.NoSuchFileException => ()
      }
    def revisions: fs2.Stream[IO, String] =
      val base = root / "renditions"
      fs2.Stream.eval(Files[IO].exists(base)).flatMap {
        case false => fs2.Stream.empty
        case true =>
          Files[IO].list(base).evalFilter(Files[IO].isDirectory(_)).map(_.fileName.toString)
      }
    def signedUrls(rev: String, asset: String, ttl: FiniteDuration): IO[Option[(String, String)]] =
      IO.none

  /** Renditions as blobs in the object store; URLs are presigned GETs. */
  final class S3(store: ObjectStore, presigning: Presigning) extends RenditionStore:
    private def prefix(rev: String) = s"renditions/$rev/"
    private def headerKey(rev: String, asset: String) = s"${prefix(rev)}$asset.json"
    private def payloadKey(rev: String, asset: String) = s"${prefix(rev)}$asset.f32"
    def write(rev: String, asset: String, header: String, payload: Array[Byte]): IO[Unit] =
      store.putBlob(headerKey(rev, asset), header.getBytes("UTF-8"), "application/json") *>
        store.putBlob(payloadKey(rev, asset), payload, "application/octet-stream")
    def ready(rev: String, asset: String): IO[Boolean] = store.blobExists(payloadKey(rev, asset))
    def header(rev: String, asset: String): IO[Option[Array[Byte]]] =
      store.getBlob(headerKey(rev, asset))
    def payload(rev: String, asset: String): IO[Option[Array[Byte]]] =
      store.getBlob(payloadKey(rev, asset))
    def delete(rev: String): IO[Unit] =
      store.listBlobs(prefix(rev)).evalMap(store.deleteBlob).compile.drain
    def revisions: fs2.Stream[IO, String] =
      store.listBlobs("renditions/").map(_.split('/')).collect { case Array(_, rev, _*) => rev }
    def signedUrls(rev: String, asset: String, ttl: FiniteDuration): IO[Option[(String, String)]] =
      (
        presigning.presignGetKey(headerKey(rev, asset), ttl),
        presigning.presignGetKey(
          payloadKey(rev, asset),
          ttl
        )
      ).mapN((h, p) => Some((h, p)))

  def of(store: ObjectStore, data: Path): RenditionStore =
    store.presigning.fold(LocalFs(data))(S3(store, _))
