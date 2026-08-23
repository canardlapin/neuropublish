package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Files
import neuropublish.api.IngestionStatus
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{
  Manifest,
  ManifestAsset,
  ManifestChecks,
  Surface,
  SurfaceVertices,
  TrustedSchemas
}
import neuropublish.rendition.{SurfaceRendition, VertexFieldRendition, VolumeRendition}
import scalafim.image.io.Nifti
import scalafim.surface.{Hemisphere, SurfaceGeometry, SurfaceKind}
import scalafim.surface.io.{GiftiReader, GiftiSurfaceReader}

/** Rendition derivation shared by the inline path ([[Ingestion]]) and the worker
  * (`neuropublish.ingestion.Worker`): canonical asset → browser rendition (plan decision 2).
  * Volumes (NIfTI → `volume-f32`), surface geometries (GIFTI → `surface-mesh`), and per-vertex
  * fields (GIFTI → `vertex-field-f32`), in `Manifest.renditionTargets` order so a field finds its
  * surface's geometry already read. Ingestion is also where the surface-vertices domain key is
  * proven (SPEC §6, admission split): a surface whose triangles do not hash to its domain's
  * `structuralFingerprint`, or a field whose vertex count is not its surface's, fails the revision
  * with a message naming the asset.
  */
object Derivation:
  final case class Derived(
      assetId: String,
      kind: String, // "volume" | "surface-mesh" | "vertex-field"
      surface: Option[String], // vertex-field: the `surfaces[].id` the values are defined on
      header: String,
      payload: Array[Byte]
  )

  /** The object streamed to a temp file (never held whole in memory), handed to `read`. */
  private def withAsset[A](
      objects: ObjectStore,
      assetId: String,
      asset: ManifestAsset,
      suffix: String
  )(read: java.nio.file.Path => Either[String, A]): IO[Either[String, A]] =
    Files[IO].tempFile(None, "np-", suffix, None).use { tmp =>
      objects.getToFile(asset.digest, tmp).flatMap {
        case false => IO.pure(Left(s"asset $assetId (${asset.digest.render}) was not uploaded"))
        case true => IO.blocking(read(tmp.toNioPath))
      }
    }

  /** Left = why this asset cannot render (missing bytes, unreadable NIfTI). */
  def derive(
      objects: ObjectStore,
      assetId: String,
      asset: ManifestAsset
  ): IO[Either[String, Derived]] =
    withAsset(objects, assetId, asset, ".nii") { path =>
      scala.util.Try(Nifti.readVol(path)).toEither.left.map(e =>
        s"asset $assetId is not a readable NIfTI volume: ${e.getMessage}"
      ).map { vol =>
        val r = VolumeRendition.encode(vol, Some(asset.digest.render))
        Derived(assetId, "volume", None, VolumeRendition.headerJson(r.header), r.payload)
      }
    }

  /** The geometry of one declared surface, proven against its domain: counts and the ADR 0005
    * `surface-vertices/v1` fingerprint recomputed from the GIFTI's triangles.
    */
  def readSurface(
      objects: ObjectStore,
      manifest: Manifest,
      surface: Surface
  ): IO[Either[String, SurfaceGeometry]] =
    manifest.asset(surface.asset) match
      case None => IO.pure(Left(s"surface ${surface.id} names undeclared asset ${surface.asset}"))
      case Some(asset) =>
        withAsset(objects, surface.asset, asset, ".surf.gii") { path =>
          val hemisphere = Hemisphere.fromString(surface.hemisphere)
          GiftiSurfaceReader.readEither(path, hemisphere, SurfaceKind.fromString(surface.kind))
            .left.map(e =>
              s"asset ${surface.asset} (surface ${surface.id}) is not a readable GIFTI surface: ${e.message}"
            ).flatMap(g => proveDomain(manifest, surface, g).map(_ => g))
        }

  private def proveDomain(
      manifest: Manifest,
      surface: Surface,
      g: SurfaceGeometry
  ): Either[String, Unit] =
    val who = s"surface ${surface.id} (asset ${surface.asset})"
    for
      p <- ManifestChecks.surfaceDomain(manifest, surface.domain).toRight(
        s"$who: domain '${surface.domain}' is not a trusted surface-vertices domain"
      )
      _ <- Either.cond(
        g.vertexCount == p.vertexCount && g.faceCount == p.faceCount,
        (),
        s"$who has ${g.vertexCount} vertices and ${g.faceCount} faces; domain '${surface.domain}' declares ${p.vertexCount} and ${p.faceCount}"
      )
      declared <- manifest.domains.find(_.id == surface.domain).flatMap(_.key)
        .flatMap(_.hcursor.get[String]("structuralFingerprint").toOption)
        .toRight(s"$who: domain '${surface.domain}' has no structuralFingerprint")
      schema = TrustedSchemas.SurfaceVerticesV1
      actual = SurfaceVertices.fingerprint(schema.id, schema.version, p, g.mesh.faceIndices).render
      _ <- Either.cond(
        actual == declared,
        (),
        s"$who: its triangles hash to $actual, but domain '${surface.domain}' declares $declared; the GIFTI does not realize the declared topology"
      )
    yield ()

  /** One scalar per vertex of `surface` from a GIFTI data array (the first array that is not the
    * geometry: NIFTI_INTENT_NONE, SHAPE, or similar), narrowed to float32.
    */
  def readField(
      objects: ObjectStore,
      assetId: String,
      asset: ManifestAsset,
      surface: Surface,
      geometry: SurfaceGeometry
  ): IO[Either[String, Derived]] =
    withAsset(objects, assetId, asset, ".func.gii") { path =>
      GiftiReader.read(path).left.map(e =>
        s"asset $assetId is not a readable GIFTI file: ${e.message}"
      ).flatMap { doc =>
        doc.dataArrays.find(a => !a.isPointSet && !a.isTriangle).toRight(
          s"asset $assetId has no per-vertex data array"
        ).flatMap(array =>
          GiftiReader.doubleData(array).left.map(e =>
            s"asset $assetId: ${e.message}"
          ).flatMap { values =>
            Either.cond(
              values.length == geometry.vertexCount, {
                val r = VertexFieldRendition.encode(surface.id, values, Some(asset.digest.render))
                Derived(
                  assetId,
                  "vertex-field",
                  Some(surface.id),
                  VertexFieldRendition.headerJson(r.header),
                  r.payload
                )
              },
              s"asset $assetId has ${values.length} vertex values but surface ${surface.id} has ${geometry.vertexCount} vertices"
            )
          }
        )
      }
    }

  /** Derive every rendition of the manifest in target order, handing each to `sink` as it is made;
    * stops at the first failure. Surface geometries are read once and kept for their fields.
    */
  def deriveEach(
      objects: ObjectStore,
      manifest: Manifest
  )(sink: Derived => IO[Unit]): IO[Either[String, Unit]] =
    def geometryOf(
        cache: Map[String, SurfaceGeometry],
        surfaceId: String
    ): IO[Either[String, (Map[String, SurfaceGeometry], SurfaceGeometry)]] =
      cache.get(surfaceId) match
        case Some(g) => IO.pure(Right((cache, g)))
        case None =>
          manifest.surface(surfaceId) match
            case None => IO.pure(Left(s"undeclared surface $surfaceId"))
            case Some(s) =>
              readSurface(objects, manifest, s).map(_.map(g => (cache + (s.id -> g), g)))
    manifest.renditionTargets
      .foldLeftM[IO, Either[String, Map[String, SurfaceGeometry]]](Right(Map.empty)) {
        case (l @ Left(_), _) => IO.pure(l)
        case (Right(cache), t) =>
          val asset = manifest.asset(t.assetId)
          t.kind match
            case "volume" =>
              derive(objects, t.assetId, asset.get).flatMap {
                case Left(m) => IO.pure(Left(m))
                case Right(d) => sink(d).as(Right(cache))
              }
            case "surface-mesh" =>
              // every surface on this asset is proven; the rendition is encoded once
              val on = manifest.surfaces.filter(_.asset == t.assetId)
              on.foldLeftM[IO, Either[String, Map[String, SurfaceGeometry]]](Right(cache)) {
                case (l @ Left(_), _) => IO.pure(l)
                case (Right(c), s) => geometryOf(c, s.id).map(_.map(_._1))
              }.flatMap {
                case Left(m) => IO.pure(Left(m))
                case Right(c) =>
                  val g = c(on.head.id)
                  SurfaceRendition.encode(g, Some(asset.get.digest.render)) match
                    case Left(m) => IO.pure(Left(s"asset ${t.assetId}: $m"))
                    case Right(r) =>
                      sink(Derived(
                        t.assetId,
                        "surface-mesh",
                        None,
                        SurfaceRendition.headerJson(r.header),
                        r.payload
                      )).as(Right(c))
              }
            case "vertex-field" =>
              geometryOf(cache, t.surface.get).flatMap {
                case Left(m) => IO.pure(Left(m))
                case Right((c, g)) =>
                  readField(objects, t.assetId, asset.get, manifest.surface(t.surface.get).get, g)
                    .flatMap {
                      case Left(m) => IO.pure(Left(m))
                      case Right(d) => sink(d).as(Right(c))
                    }
              }
            case other => IO.pure(Left(s"asset ${t.assetId}: unknown rendition kind $other"))
      }.map(_.map(_ => ()))

  /** Every rendition of the manifest; stops at the first failure. */
  def deriveAll(objects: ObjectStore, manifest: Manifest): IO[Either[String, List[Derived]]] =
    cats.effect.Ref.of[IO, List[Derived]](Nil).flatMap { acc =>
      deriveEach(objects, manifest)(d => acc.update(d :: _)).flatMap {
        case Left(m) => IO.pure(Left(m))
        case Right(()) => acc.get.map(ds => Right(ds.reverse))
      }
    }

  /** Derive and store every rendition of `revisionId` (the worker's unit of work). */
  def ingest(
      objects: ObjectStore,
      renditions: RenditionStore,
      revisionId: String,
      manifest: Manifest
  ): IO[Either[String, Unit]] =
    deriveEach(objects, manifest)(d => renditions.write(revisionId, d.assetId, d.header, d.payload))

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
    manifest.renditionAssetIds.forallM(renditions.ready(revisionId, _)).map {
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
