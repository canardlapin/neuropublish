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
import neuropublish.rendition.{
  SourceTransform,
  SurfaceRendition,
  VertexFieldRendition,
  VolumeRendition
}
import scalafim.image.DMat
import scalafim.image.io.Nifti
import scalafim.surface.{Hemisphere, SurfaceGeometry, SurfaceKind, TriangleMesh}
import scalafim.surface.gifti.{GiftiDataArray, GiftiDataType, GiftiDocument}
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

  /** One surface asset read, placed in world coordinates, and proven against its domain: the
    * geometry carries world positions and an identity `surfaceToWorld` (the GIFTI's transform is
    * applied here, once, and recorded as provenance), the domain payload it realizes, and what the
    * GIFTI itself said about its anatomy.
    */
  final case class SurfaceSource(
      geometry: SurfaceGeometry,
      domain: SurfaceVertices.Payload,
      sourceTransform: Option[SourceTransform],
      anatomicalStructurePrimary: Option[String]
  )

  /** GIFTI `TransformedSpace` values this build can place in RAS+ world millimetres. A transform
    * into any other space (`NIFTI_XFORM_UNKNOWN` above all) is not guessed at (SPEC §5).
    */
  val WorldSpaces: Set[String] = Set(
    "NIFTI_XFORM_SCANNER_ANAT",
    "NIFTI_XFORM_ALIGNED_ANAT",
    "NIFTI_XFORM_TALAIRACH",
    "NIFTI_XFORM_MNI_152"
  )

  private val Float64 = "NIFTI_TYPE_FLOAT64"

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

  /** The data type as the GIFTI wrote it, uppercased (`NIFTI_TYPE_*`). */
  private def dataTypeCode(a: GiftiDataArray): String = a.dataType match
    case GiftiDataType.Other(v) => v.trim.toUpperCase
    case known => known.code

  /** `AnatomicalStructurePrimary` as the GIFTI wrote it: on the array, else on the document. */
  private def anatomicalStructure(doc: GiftiDocument, array: GiftiDataArray): Option[String] =
    array.metadata.get("AnatomicalStructurePrimary")
      .orElse(doc.metadata.get("AnatomicalStructurePrimary"))
      .map(_.trim).filter(_.nonEmpty)

  /** The hemisphere a GIFTI structure name states, when it states one unambiguously. */
  def hemisphereOfStructure(value: String): Option[String] =
    value.trim.toUpperCase.replace("_", "").replace(" ", "") match
      case "CORTEXLEFT" => Some("left")
      case "CORTEXRIGHT" => Some("right")
      case _ => None

  private def isIdentity(m: Vector[Double]): Boolean =
    m.length == 16 && (0 until 16).forall(i => m(i) == (if i % 5 == 0 then 1.0 else 0.0))

  private def rows(m: Vector[Double]): Vector[Vector[Double]] =
    Vector.tabulate(4, 4)((r, c) => m(r * 4 + c))

  /** `coordinates` (x, y, z per vertex) through the 4x4 row-major `m`. */
  private def applyTransform(coordinates: Array[Double], m: Vector[Double]): Array[Double] =
    val out = new Array[Double](coordinates.length)
    var i = 0
    while i < coordinates.length do
      val x = coordinates(i); val y = coordinates(i + 1); val z = coordinates(i + 2)
      out(i) = m(0) * x + m(1) * y + m(2) * z + m(3)
      out(i + 1) = m(4) * x + m(5) * y + m(6) * z + m(7)
      out(i + 2) = m(8) * x + m(9) * y + m(10) * z + m(11)
      i += 3
    out

  /** One hemisphere's geometry in RAS+ world millimetres, from the GIFTI document as written.
    *
    * The GIFTI's `CoordinateSystemTransformMatrix` is applied here — the only place it is applied —
    * so the rendition payload is world positions and its `surfaceToWorld` is the identity; the
    * matrix and its spaces are kept as `sourceTransform` for provenance. A transform into a space
    * this build cannot place (`NIFTI_XFORM_UNKNOWN` and friends) is refused rather than guessed at,
    * unless it is the identity and so says nothing. Where the GIFTI states its
    * `AnatomicalStructurePrimary` it must agree with the declared hemisphere: left and right meshes
    * of one template share a vertex count and a topology, so nothing downstream would catch the
    * swap.
    */
  def worldGeometry(
      assetId: String,
      surfaceId: String,
      doc: GiftiDocument,
      hemisphere: String,
      kind: String
  ): Either[String, (SurfaceGeometry, Option[SourceTransform], Option[String])] =
    val who = s"asset $assetId (surface $surfaceId)"
    for
      pointSet <- doc.pointSet.toRight(s"$who has no NIFTI_INTENT_POINTSET data array")
      _ <- Either.cond(
        dataTypeCode(pointSet) != Float64,
        (),
        s"$who has $Float64 coordinates; this revision reads float32 GIFTI sources only (write NIFTI_TYPE_FLOAT32)"
      )
      structure = anatomicalStructure(doc, pointSet)
      _ <- structure match
        case Some(v) if !hemisphereOfStructure(v).contains(hemisphere) =>
          Left(
            s"$who is declared the $hemisphere hemisphere but its GIFTI says AnatomicalStructurePrimary=$v"
          )
        case _ => Right(())
      chosen <- pointSet.transforms.toList match
        case Nil => Right(None)
        case ts =>
          ts.find(t => t.transformedSpace.exists(s => WorldSpaces(s.trim.toUpperCase))) match
            case Some(t) => Right(Some((t, true)))
            case None if ts.forall(t => isIdentity(t.matrixData)) => Right(Some((ts.head, false)))
            case None =>
              val t = ts.head
              Left(
                s"$who carries a non-identity CoordinateSystemTransformMatrix into a space this build cannot place (" +
                  s"${t.dataSpace.getOrElse("no DataSpace")} → ${t.transformedSpace.getOrElse("no TransformedSpace")}" +
                  "); it cannot be placed in RAS+ world coordinates"
              )
      read <- GiftiSurfaceReader.geometry(
        doc,
        Hemisphere.fromString(hemisphere),
        SurfaceKind.fromString(kind)
      ).left.map(e =>
        s"asset $assetId (surface $surfaceId) is not a readable GIFTI surface: ${e.message}"
      )
      placed <- {
        val coordinates = chosen match
          case Some((t, true)) => applyTransform(read.mesh.coordinates, t.matrixData)
          case _ => read.mesh.coordinates
        scala.util.Try(
          SurfaceGeometry(
            TriangleMesh.fromArrays(coordinates, read.mesh.faceIndices),
            read.hemisphere,
            read.kind,
            DMat.eye(4)
          )
        ).toEither.left.map(e => s"$who: ${e.getMessage}")
      }
    yield (
      placed,
      chosen.map((t, _) => SourceTransform(rows(t.matrixData), t.dataSpace, t.transformedSpace)),
      structure
    )

  /** The geometry of one declared surface, placed in world coordinates and proven against its
    * domain: counts and the ADR 0005 `surface-vertices/v1` fingerprint recomputed from the GIFTI's
    * triangles.
    */
  def readSurface(
      objects: ObjectStore,
      manifest: Manifest,
      surface: Surface
  ): IO[Either[String, SurfaceSource]] =
    manifest.asset(surface.asset) match
      case None => IO.pure(Left(s"surface ${surface.id} names undeclared asset ${surface.asset}"))
      case Some(asset) =>
        withAsset(objects, surface.asset, asset, ".surf.gii") { path =>
          GiftiReader.read(path).left.map(e =>
            s"asset ${surface.asset} (surface ${surface.id}) is not a readable GIFTI surface: ${e.message}"
          ).flatMap(doc =>
            for
              placed <- worldGeometry(
                surface.asset,
                surface.id,
                doc,
                surface.hemisphere,
                surface.kind
              )
              (geometry, transform, structure) = placed
              payload <- proveDomain(manifest, surface, geometry)
            yield SurfaceSource(geometry, payload, transform, structure)
          )
        }

  private def proveDomain(
      manifest: Manifest,
      surface: Surface,
      g: SurfaceGeometry
  ): Either[String, SurfaceVertices.Payload] =
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
      actual = SurfaceVertices.fingerprint(schema.id, schema.version, p, g.mesh.faceIndices)
      _ <- Either.cond(
        Sha256.parse(declared).map(_.hex) == Right(actual.hex),
        (),
        s"$who: its triangles hash to ${actual.render}, but domain '${surface.domain}' declares $declared; the GIFTI does not realize the declared topology"
      )
    yield p

  /** One scalar per vertex of `surface` from a GIFTI document: the first data array that is not the
    * geometry (`NIFTI_INTENT_NONE`, `SHAPE`, or similar), narrowed to float32 by the caller.
    *
    * A sparse file — one carrying `NIFTI_INTENT_NODE_INDEX`, the layout Workbench and nibabel write
    * for a field defined on part of a surface — is refused by name rather than having its vertex
    * *indices* stored as values, and a rank-2 array (a `TIME_SERIES` of V×T, say) is refused for
    * what it is rather than through a vertex-count message that names the wrong fault.
    */
  def fieldValues(
      assetId: String,
      doc: GiftiDocument,
      surfaceId: String,
      vertexCount: Int
  ): Either[String, Array[Double]] =
    for
      _ <- Either.cond(
        !doc.dataArrays.exists(_.isNodeIndex),
        (),
        s"asset $assetId carries a NIFTI_INTENT_NODE_INDEX array: sparse vertex fields are not supported in vertex-field-f32@0 (write one value per vertex of surface $surfaceId)"
      )
      array <- doc.dataArrays.find(a => !a.isPointSet && !a.isTriangle).toRight(
        s"asset $assetId has no per-vertex data array"
      )
      _ <- Either.cond(
        array.rank == 1 || (array.rank == 2 && array.columns == 1),
        (),
        s"asset $assetId has a ${array.dims.mkString("×")} ${array.intent.code} data array; vertex-field-f32@0 takes one scalar per vertex (Dimensionality 1, or Dim1=1)"
      )
      _ <- Either.cond(
        dataTypeCode(array) != Float64,
        (),
        s"asset $assetId is $Float64; this revision reads float32 vertex fields only (write NIFTI_TYPE_FLOAT32)"
      )
      values <- GiftiReader.doubleData(array).left.map(e => s"asset $assetId: ${e.message}")
      _ <- Either.cond(
        values.length == vertexCount,
        (),
        s"asset $assetId has ${values.length} vertex values but surface $surfaceId has $vertexCount vertices"
      )
    yield values

  /** The `vertex-field-f32@0` rendition of one per-vertex GIFTI asset on `surface`. */
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
      ).flatMap(doc => fieldValues(assetId, doc, surface.id, geometry.vertexCount)).map { values =>
        val r = VertexFieldRendition.encode(surface.id, values, Some(asset.digest.render))
        Derived(
          assetId,
          "vertex-field",
          Some(surface.id),
          VertexFieldRendition.headerJson(r.header),
          r.payload
        )
      }
    }

  /** Derive every rendition of the manifest in target order, handing each to `sink` as it is made;
    * stops at the first failure. A surface asset is read, placed, and proven once and kept for its
    * fields (the cache is keyed by asset: admission allows one `surfaces[]` entry per asset).
    */
  def deriveEach(
      objects: ObjectStore,
      manifest: Manifest
  )(sink: Derived => IO[Unit]): IO[Either[String, Unit]] =
    type Cache = Map[String, SurfaceSource] // by asset id
    def sourceOf(
        cache: Cache,
        surfaceId: String
    ): IO[Either[String, (Cache, SurfaceSource)]] =
      manifest.surface(surfaceId) match
        case None => IO.pure(Left(s"undeclared surface $surfaceId"))
        case Some(s) =>
          cache.get(s.asset) match
            case Some(g) => IO.pure(Right((cache, g)))
            case None =>
              readSurface(objects, manifest, s).map(_.map(g => (cache + (s.asset -> g), g)))
    manifest.renditionTargets
      .foldLeftM[IO, Either[String, Cache]](Right(Map.empty)) {
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
              on.foldLeftM[IO, Either[String, Cache]](Right(cache)) {
                case (l @ Left(_), _) => IO.pure(l)
                case (Right(c), s) => sourceOf(c, s.id).map(_.map(_._1))
              }.flatMap {
                case Left(m) => IO.pure(Left(m))
                case Right(c) =>
                  val src = c(t.assetId)
                  SurfaceRendition.encode(
                    src.geometry,
                    src.domain.space,
                    Some(asset.get.digest.render),
                    src.sourceTransform,
                    src.anatomicalStructurePrimary
                  ) match
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
              sourceOf(cache, t.surface.get).flatMap {
                case Left(m) => IO.pure(Left(m))
                case Right((c, src)) =>
                  readField(
                    objects,
                    t.assetId,
                    asset.get,
                    manifest.surface(t.surface.get).get,
                    src.geometry
                  ).flatMap {
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
