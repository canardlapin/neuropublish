package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest
import neuropublish.rendition.VolumeRendition
import scalafim.image.io.Nifti

/** Derives browser renditions from committed canonical assets (plan decision 2). Stage 1 runs it
  * in-process right after commit; Stage 2 moves it to a separate worker with a queue. Output lives
  * outside the manifest digest.
  */
final class Ingestion(objects: ObjectStore, root: Path):
  private def dir(revisionId: String) = root / "renditions" / revisionId

  def headerPath(revisionId: String, assetId: String): Path = dir(revisionId) / s"$assetId.json"
  def payloadPath(revisionId: String, assetId: String): Path = dir(revisionId) / s"$assetId.f32"

  def status(revisionId: String, assetId: String): IO[String] =
    Files[IO].exists(payloadPath(revisionId, assetId)).map(if _ then "ready" else "pending")

  def run(revisionId: String, manifest: Manifest): IO[List[String]] =
    Files[IO].createDirectories(dir(revisionId)) *>
      manifest.volumeAssetIds.traverse { assetId =>
        val asset = manifest.asset(assetId).get
        objects.get(asset.digest).flatMap {
          case None => IO.raiseError(
              IllegalStateException(s"asset ${asset.digest.render} missing at ingestion")
            )
          case Some(bytes) =>
            Files[IO].tempFile(None, "np-", ".nii", None).use { tmp =>
              fs2.Stream.emits(bytes).through(Files[IO].writeAll(tmp)).compile.drain *>
                IO.blocking(Nifti.readVol(tmp.toNioPath)).flatMap { vol =>
                  val r = VolumeRendition.encode(vol, Some(asset.digest.render))
                  fs2.Stream.emit(
                    VolumeRendition.headerJson(r.header)
                  ).through(Files[IO].writeUtf8(headerPath(revisionId, assetId))).compile.drain *>
                    fs2.Stream.emits(r.payload).through(Files[IO].writeAll(payloadPath(
                      revisionId,
                      assetId
                    ))).compile.drain
                }
            }.as(assetId)
        }
      }
