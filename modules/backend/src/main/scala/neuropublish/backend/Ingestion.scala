package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import neuropublish.protocol.json.Manifest
import neuropublish.rendition.VolumeRendition
import scalafim.image.io.Nifti

/** Derives browser renditions from canonical assets (plan decision 2). Stage 1 runs it in-process:
  * renditions are staged under the upload session before the revision is recorded, so an unreadable
  * asset fails the push instead of committing a revision that can never render; commit then
  * publishes the staged directory by rename. Stage 2 moves derivation to a separate worker with an
  * explicit per-revision status. Output lives outside the manifest digest.
  */
final class Ingestion(objects: ObjectStore, root: Path):
  private def dir(revisionId: String) = root / "renditions" / revisionId
  private def staging(sessionId: String) = root / "staging" / sessionId

  def headerPath(revisionId: String, assetId: String): Path = dir(revisionId) / s"$assetId.json"
  def payloadPath(revisionId: String, assetId: String): Path = dir(revisionId) / s"$assetId.f32"

  def status(revisionId: String, assetId: String): IO[String] =
    Files[IO].exists(payloadPath(revisionId, assetId)).map(if _ then "ready" else "pending")

  /** Derive every volume rendition into the session's staging directory. Left = which asset failed
    * and why.
    */
  def stage(sessionId: String, manifest: Manifest): IO[Either[String, Unit]] =
    val out = staging(sessionId)
    discard(sessionId) *> Files[IO].createDirectories(out) *>
      manifest.volumeAssetIds.traverse { assetId =>
        val asset = manifest.asset(assetId).get
        objects.get(asset.digest).flatMap {
          case None => IO.pure(Left(s"asset $assetId (${asset.digest.render}) was not uploaded"))
          case Some(bytes) =>
            Files[IO].tempFile(None, "np-", ".nii", None).use { tmp =>
              fs2.Stream.emits(bytes).through(Files[IO].writeAll(tmp)).compile.drain *>
                IO.blocking(Nifti.readVol(tmp.toNioPath)).attempt.flatMap {
                  case Left(e) =>
                    IO.pure(Left(s"asset $assetId is not a readable NIfTI volume: ${e.getMessage}"))
                  case Right(vol) =>
                    val r = VolumeRendition.encode(vol, Some(asset.digest.render))
                    fs2.Stream.emit(
                      VolumeRendition.headerJson(r.header)
                    ).through(Files[IO].writeUtf8(out / s"$assetId.json")).compile.drain *>
                      fs2.Stream.emits(r.payload).through(Files[IO].writeAll(out /
                        s"$assetId.f32")).compile.drain.as(Right(()))
                }
            }
        }
      }.map(_.collectFirst { case Left(m) => m }.toLeft(()))

  /** Publish staged renditions under the committed revision id. */
  def publish(sessionId: String, revisionId: String): IO[Unit] =
    Files[IO].createDirectories(dir(revisionId).parent.get) *>
      Files[IO].move(staging(sessionId), dir(revisionId))

  def discard(sessionId: String): IO[Unit] =
    Files[IO].exists(staging(sessionId)).flatMap(if _ then
      Files[IO].deleteRecursively(staging(sessionId))
    else IO.unit)
