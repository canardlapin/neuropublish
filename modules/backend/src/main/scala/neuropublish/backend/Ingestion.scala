package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Files
import neuropublish.api.IngestionStatus
import neuropublish.protocol.json.{Manifest, ManifestAsset}
import neuropublish.rendition.VolumeRendition
import scalafim.image.io.Nifti

/** Rendition derivation shared by the inline path ([[Ingestion]]) and the worker
  * (`neuropublish.ingestion.Worker`): canonical volume asset → browser rendition (plan decision 2).
  */
object Derivation:
  final case class Derived(assetId: String, header: String, payload: Array[Byte])

  /** Left = why this asset cannot render (missing bytes, unreadable NIfTI). */
  def derive(
      objects: ObjectStore,
      assetId: String,
      asset: ManifestAsset
  ): IO[Either[String, Derived]] =
    objects.get(asset.digest).flatMap {
      case None => IO.pure(Left(s"asset $assetId (${asset.digest.render}) was not uploaded"))
      case Some(bytes) =>
        Files[IO].tempFile(None, "np-", ".nii", None).use { tmp =>
          fs2.Stream.emits(bytes).through(Files[IO].writeAll(tmp)).compile.drain *>
            IO.blocking(Nifti.readVol(tmp.toNioPath)).attempt.map {
              case Left(e) =>
                Left(s"asset $assetId is not a readable NIfTI volume: ${e.getMessage}")
              case Right(vol) =>
                val r = VolumeRendition.encode(vol, Some(asset.digest.render))
                Right(Derived(assetId, VolumeRendition.headerJson(r.header), r.payload))
            }
        }
    }

  /** Every volume asset of the manifest; stops at the first failure. */
  def deriveAll(objects: ObjectStore, manifest: Manifest): IO[Either[String, List[Derived]]] =
    manifest.volumeAssetIds.traverse(id => derive(objects, id, manifest.asset(id).get))
      .map(_.sequence)

  /** Derive and store every rendition of `revisionId` (the worker's unit of work). */
  def ingest(
      objects: ObjectStore,
      renditions: RenditionStore,
      revisionId: String,
      manifest: Manifest
  ): IO[Either[String, Unit]] =
    manifest.volumeAssetIds.foldLeftM(Either.right[String, Unit](())) {
      case (l @ Left(_), _) => IO.pure(l)
      case (Right(()), id) =>
        derive(objects, id, manifest.asset(id).get).flatMap {
          case Left(m) => IO.pure(Left(m))
          case Right(d) => renditions.write(revisionId, id, d.header, d.payload).as(Right(()))
        }
    }

/** How commit hands work to ingestion. `Inline`: derive before the revision is recorded, so an
  * unreadable asset fails the push (the Stage 1 behaviour, default). `Worker`: commit enqueues and
  * returns; a separate process derives, and the revision reports `ingestion.status = pending` until
  * it is done (NP_INGESTION=worker).
  */
enum IngestionMode:
  case Inline, Worker

final class Ingestion(
    objects: ObjectStore,
    val renditions: RenditionStore,
    val queue: IngestionQueue,
    val mode: IngestionMode
):
  /** Renditions staged by an inline commit before the revision id exists. */
  def stage(manifest: Manifest): IO[Either[String, List[Derivation.Derived]]] =
    Derivation.deriveAll(objects, manifest)

  def publish(revisionId: String, staged: List[Derivation.Derived]): IO[Unit] =
    staged.traverse_(d => renditions.write(revisionId, d.assetId, d.header, d.payload))

  /** Per-asset wire status: ready once its rendition exists, else the job's state. */
  def assetStatus(revisionId: String, assetId: String, job: Option[IngestionJob]): IO[String] =
    renditions.ready(revisionId, assetId).map {
      case true => "ready"
      case false => job.map(_.status).filter(_ == "failed").getOrElse("pending")
    }

  /** The revision's ingestion record. An inline-ingested revision has no job: it is ready by
    * construction.
    */
  def status(revisionId: String, committedAt: String): IO[IngestionStatus] =
    queue.status(revisionId).map(_.map(_.api).getOrElse(IngestionStatus("ready", committedAt)))

object Ingestion:
  def modeFromEnv(env: Map[String, String]): IngestionMode =
    env.get("NP_INGESTION").map(_.trim.toLowerCase) match
      case Some("worker") => IngestionMode.Worker
      case _ => IngestionMode.Inline
