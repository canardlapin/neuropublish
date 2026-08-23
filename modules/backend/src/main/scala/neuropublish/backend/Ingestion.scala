package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Files
import neuropublish.api.IngestionStatus
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{Manifest, ManifestAsset}
import neuropublish.rendition.VolumeRendition
import scalafim.image.io.Nifti

/** Rendition derivation shared by the inline path ([[Ingestion]]) and the worker
  * (`neuropublish.ingestion.Worker`): canonical volume asset → browser rendition (plan decision 2).
  */
object Derivation:
  final case class Derived(assetId: String, header: String, payload: Array[Byte])

  /** Left = why this asset cannot render (missing bytes, unreadable NIfTI). The object is streamed
    * to a temp file, never held in memory as a whole.
    */
  def derive(
      objects: ObjectStore,
      assetId: String,
      asset: ManifestAsset
  ): IO[Either[String, Derived]] =
    Files[IO].tempFile(None, "np-", ".nii", None).use { tmp =>
      objects.getToFile(asset.digest, tmp).flatMap {
        case false => IO.pure(Left(s"asset $assetId (${asset.digest.render}) was not uploaded"))
        case true =>
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

  /** The stored manifest of a revision, parsed — and refused ([[IntegrityError]]) when its bytes no
    * longer hash to the digest the record names: a tampered object is never used silently.
    */
  def manifestOf(objects: ObjectStore, rec: RevisionRecord): IO[Option[Manifest]] =
    val declared = Sha256.unsafe(rec.manifestDigest.stripPrefix("sha256:"))
    objects.get(declared).flatMap {
      case None => IO.none
      case Some(bytes) =>
        val actual = Sha256.of(bytes)
        if actual.hex != declared.hex then
          IO.raiseError(IntegrityError(
            s"revision ${rec.id}: stored manifest hashes to ${actual.render}, record says ${declared.render}"
          ))
        else
          Manifest.parse(bytes) match
            case Left(ps) =>
              IO.raiseError(IntegrityError(
                s"revision ${rec.id}: stored manifest no longer parses: ${neuropublish.protocol.json.Problem.render(ps)}"
              ))
            case Right((_, m)) => IO.pure(Some(m))
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

  /** The revision's ingestion record, derived from evidence: every rendition present → `ready`;
    * otherwise the job's state; otherwise — renditions missing and no job to produce them —
    * `failed`. A revision is never reported ready merely because no job was found.
    */
  def status(
      revisionId: String,
      committedAt: String,
      manifest: Manifest,
      job: Option[IngestionJob]
  ): IO[IngestionStatus] =
    manifest.volumeAssetIds.forallM(renditions.ready(revisionId, _)).map {
      case true =>
        job.map(_.api).filter(_.status == "ready")
          .getOrElse(IngestionStatus("ready", job.map(_.updatedAt).getOrElse(committedAt)))
      case false =>
        job.map(_.api).getOrElse(IngestionStatus(
          "failed",
          committedAt,
          Some(
            if mode == IngestionMode.Worker then "no ingestion job"
            else "renditions missing and no ingestion job to derive them"
          )
        ))
    }

object Ingestion:
  def modeFromEnv(env: Map[String, String]): IngestionMode =
    env.get("NP_INGESTION").map(_.trim.toLowerCase) match
      case Some("worker") => IngestionMode.Worker
      case _ => IngestionMode.Inline

  /** Ingestion states in which a revision's renditions are not (yet) all available. */
  def notReady(s: IngestionStatus): Boolean =
    s.status == "pending" || s.status == "running" || s.status == "failed"
