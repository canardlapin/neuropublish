package neuropublish.frontend

import neuropublish.api.*
import neuropublish.protocol.json.*
import neuropublish.rendition.{
  ScalarSummary,
  SurfaceRendition,
  VertexFieldRendition,
  VolumeRendition
}
import neuropublish.viewer.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scalafim.image.NeuroVol
import scalafim.surface.{SurfaceField, SurfaceGeometry}

/** A surface declared by the manifest (`surfaces[]`): one hemisphere's geometry asset. */
final case class SurfaceDecl(
    id: String,
    asset: String,
    domain: String,
    hemisphere: String, // normalised: "left" | "right" | other
    kind: String,
    label: String
)

/** A field's surface representation: per-vertex values (`asset`) on a declared surface. */
final case class SurfaceRep(asset: String, surface: String, hemisphere: String)

/** A revision with its renditions decoded: everything the workspace needs, loaded once. Surface
  * geometry and vertex fields are decoded per hemisphere from their own renditions (by `kind`);
  * nothing here derives one representation from another.
  */
final case class Loaded(
    workspace: String,
    project: String,
    detail: RevisionDetail,
    manifest: Manifest,
    volumes: Map[String, NeuroVol[Double]],
    summaries: Map[String, ScalarSummary], // by asset id: volumes and vertex fields
    surfaces: Map[String, (SurfaceDecl, SurfaceGeometry)], // by surface id (e.g. "lh-pial")
    vertexFields: Map[String, SurfaceField[Double]], // by asset id
    surfaceSpaces: Map[String, String] = Map.empty // by surface id: the rendition header's `space`
):
  private val surfaceRepsByField: Map[String, List[SurfaceRep]] = Loaded.surfaceReps(manifest)

  /** Declared surfaces whose geometry decoded, in manifest order. */
  private val decodedSurfaces: List[SurfaceDecl] =
    manifest.surfaces.map(_.id).flatMap(surfaces.get).map(_._1)

  /** Surface representations whose vertex field and surface geometry both decoded, placed or not.
    */
  private def decodedSurfaceRepsOf(f: ResultField): List[SurfaceRep] =
    surfaceRepsByField.getOrElse(f.id, Nil).filter(r =>
      vertexFields.contains(r.asset) && surfaces.contains(r.surface)
    )

  /** The surface occupying each hemisphere slot ("left" | "right"): `SurfacePlacement`'s rule. */
  val placedSurfaces: Map[String, SurfaceDecl] =
    SurfacePlacement.place(
      decodedSurfaces,
      manifest.resultFields.flatMap(decodedSurfaceRepsOf).map(_.surface).toSet
    )

  def isPlaced(surfaceId: String): Boolean = placedSurfaces.values.exists(_.id == surfaceId)

  /** Decoded left/right surfaces that lost their hemisphere's slot: declared, but not drawn. */
  def unplacedSurfaces: List[SurfaceDecl] =
    decodedSurfaces
      .filter(d => (d.hemisphere == "left" || d.hemisphere == "right") && !isPlaced(d.id))
      .sortBy(_.id)

  /** Result fields with at least one drawable representation, in manifest order. */
  def fields: List[ResultField] =
    manifest.resultFields
      .filter(f => volumeAssetOf(f).isDefined || surfaceRepsOf(f).nonEmpty)
      .sortBy(_.order.getOrElse(Int.MaxValue))

  /** Result fields that have a volume representation with a ready rendition, in manifest order. */
  def volumeFields: List[ResultField] = fields.filter(f => volumeAssetOf(f).isDefined)

  def field(id: String): Option[ResultField] = fields.find(_.id == id)

  def volumeAssetOf(f: ResultField): Option[String] =
    f.representations.find(r => r.kind == "volume" && volumes.contains(r.asset)).map(_.asset)

  /** Surface representations that are actually drawn: decoded, and on a *placed* surface. A field
    * has at most one per hemisphere.
    */
  def surfaceRepsOf(f: ResultField): List[SurfaceRep] =
    decodedSurfaceRepsOf(f).filter(r => isPlaced(r.surface)).distinctBy(_.hemisphere)

  /** Decoded surface representations that are not drawn because their surface is not the placed one
    * for its hemisphere (stated on the card, never silently dropped).
    */
  def undrawnSurfaceRepsOf(f: ResultField): List[SurfaceRep] =
    decodedSurfaceRepsOf(f).filterNot(r => isPlaced(r.surface))

  def representationsOf(f: ResultField): LayerRepresentations =
    LayerRepresentations(volumeAssetOf(f).isDefined, surfaceRepsOf(f).map(_.hemisphere).toSet)

  /** The placed surface of a hemisphere ("left" | "right") with its geometry. */
  def surface(hemisphere: String): Option[(SurfaceDecl, SurfaceGeometry)] =
    placedSurfaces.get(hemisphere).flatMap(d => surfaces.get(d.id))

  def hasSurfaces: Boolean = surfaces.nonEmpty

  /** The underlay's `volume-grid` domain payload `space`, when the manifest states one. */
  val volumeSpace: Option[String] =
    manifest.underlays.headOption.flatMap(u => manifest.domains.find(_.id == u.domain)).flatMap(d =>
      d.descriptor.payload.hcursor.downField("space").as[String].toOption
    )

  /** A surface representation's derivation record, from the manifest's provenance activities:
    * `(activity id, schema id @ version, method/software line)`. `None` when the representation
    * declares no `derivation` or names an unknown activity — stated, never inferred.
    */
  def derivationOf(f: ResultField, surfaceId: String): Option[(String, String, Option[String])] =
    f.representations.find(r => r.kind == "surface" && r.surface.contains(surfaceId))
      .flatMap(_.derivation).flatMap { id =>
        val activities = manifest.raw.hcursor.downField("provenance").downField("activities")
        activities.values.toList.flatten.map(_.hcursor).find(_.get[String]("id").contains(id)).map {
          a =>
            val schema = a.downField("schema")
            val sid = schema.get[String]("id").getOrElse("(unknown record)")
            val ver = schema.get[String]("version").map(v => s" @ $v").getOrElse("")
            val line = List("method", "software", "tool").flatMap(k =>
              a.downField("payload").get[String](k).toOption.map(v => s"$k: $v")
            ).headOption
            (id, sid + ver, line)
        }
      }

  /** The summary the layer card and data-derived defaults use: the volume's, else the first vertex
    * field's.
    */
  def summaryOf(f: ResultField): Option[ScalarSummary] =
    volumeAssetOf(f).flatMap(summaries.get)
      .orElse(surfaceRepsOf(f).iterator.flatMap(r => summaries.get(r.asset)).nextOption())

  /** A human label for a field: estimand · measure. */
  def labelOf(f: ResultField): String =
    val est = manifest.analyses.flatMap(_.estimands).find(_.id == f.estimand).map(_.label)
    est.fold(neuropublish.protocol.Measures.label(f.measure))(e =>
      s"$e · ${neuropublish.protocol.Measures.label(f.measure)}"
    )

  /** The producer's recommendation, or — when the manifest carries none — a data-derived default
    * (window = data range, no threshold) that the layer card labels "default" rather than
    * "published". Unknown threshold modes are not guessed: they become `off`.
    */
  def published(f: ResultField): LayerDisplay =
    val c = f.publishedDisplay.map(_.hcursor)
    val sm = summaryOf(f)
    val lo = c.flatMap(
      _.downField("window").downField("min").as[Double].toOption
    ).orElse(sm.map(_.min)).getOrElse(-1.0)
    val hi = c.flatMap(
      _.downField("window").downField("max").as[Double].toOption
    ).orElse(sm.map(_.max)).getOrElse(1.0)
    val thr = c.flatMap(_.downField("threshold").downField("min").as[Double].toOption).filter(m =>
      m.isFinite && m >= 0
    ).map { m =>
      val mode = c.flatMap(
        _.downField("threshold").downField("mode").as[String].toOption
      ).getOrElse("two-sided")
      Threshold(if Threshold.Modes(mode) then mode else "off", m)
    }.getOrElse(Threshold("off", 0.0))
    val cmap = c.flatMap(
      _.downField("colormap").as[String].toOption
    ).filter(Colormap.valid).getOrElse("cold-hot")
    // Product rule (product definition, MVP scope): inferential measures are shown by default, descriptive ones are available.
    val visibleByDefault = f.measure.endsWith("/t-statistic") || f.measure.endsWith("/z-statistic")
    LayerDisplay(
      visible = visibleByDefault,
      opacity = 0.85,
      window = Window(math.min(lo, hi - 1e-9), hi),
      threshold = thr,
      colormap = cmap
    )

  def initialWorkspace: Workspace =
    Workspace(
      fields.map(f =>
        WorkspaceLayer(
          f.id,
          published(f),
          published(f),
          recommended = f.publishedDisplay.isDefined,
          representations = representationsOf(f)
        )
      ).toVector,
      None,
      WorkspaceLayout.default,
      "layers"
    )

object Loaded:
  def normaliseHemisphere(h: String): String = h.trim.toLowerCase match
    case "left" | "lh" | "l" => "left"
    case "right" | "rh" | "r" => "right"
    case other => other

  /** `surfaces[]` from the manifest, hemispheres normalised. */
  def surfaceDecls(m: Manifest): List[SurfaceDecl] =
    m.surfaces.map(s =>
      SurfaceDecl(s.id, s.asset, s.domain, normaliseHemisphere(s.hemisphere), s.kind, s.label)
    )

  /** `representations[] {kind: "surface", asset, surface, hemisphere}` per field id. A
    * representation missing its `surface` is ignored: it cannot be displayed anywhere. The
    * hemisphere defaults to the declared surface's.
    */
  def surfaceReps(m: Manifest): Map[String, List[SurfaceRep]] =
    val decls = surfaceDecls(m).map(d => d.id -> d).toMap
    m.resultFields.map { f =>
      f.id -> f.representations.flatMap { r =>
        for
          _ <- Option.when(r.kind == "surface")(())
          surface <- r.surface
        yield SurfaceRep(
          r.asset,
          surface,
          normaliseHemisphere(
            r.hemisphere.orElse(decls.get(surface).map(_.hemisphere)).getOrElse("")
          )
        )
      }
    }.toMap

  def load(api: Api, ws: String, project: String, revisionId: Option[String]): Future[Loaded] =
    for
      id <- revisionId.fold(api.project(ws, project).map(
        _.head.getOrElse(throw RuntimeException("This project has no revisions yet."))
      ))(Future.successful)
      detail <- api.revision(id)
      loaded <- fromDetail(ws, project, detail, api.rendition)
    yield loaded

  private def orFail[A](asset: String, e: Either[String, A]): A =
    e.fold(m => throw RuntimeException(s"$asset: $m"), identity)

  /** Decode a revision's renditions through `fetch` — the member route for explore, the
    * share-secret route for a link viewer (same bytes, different authorization). Renditions are
    * dispatched by `kind`; vertex fields decode against the geometry of the surface they are
    * defined on (`RenditionRef.surface`), which is why meshes are decoded first.
    */
  def fromDetail(
      ws: String,
      project: String,
      detail: RevisionDetail,
      fetch: RenditionRef => Future[(String, Array[Byte])]
  ): Future[Loaded] =
    for
      manifest <- Future.fromTry(detail.manifest.as[Manifest].toTry)
      ready = detail.renditions.filter(_.status == "ready")
      fetched <- Future.traverse(ready)(r => fetch(r).map(hb => (r, hb._1, hb._2)))
    yield
      val decls = surfaceDecls(manifest)
      val vols = fetched.collect {
        case (r, hdr, bytes) if r.kind == "volume" =>
          val h = orFail(r.assetId, VolumeRendition.decodeHeader(hdr))
          (r.assetId, orFail(r.assetId, VolumeRendition.decode(h, bytes)), h.summary)
      }
      val meshes = fetched.collect {
        case (r, hdr, bytes) if r.kind == "surface-mesh" =>
          val h = orFail(r.assetId, SurfaceRendition.decodeHeader(hdr))
          r.assetId -> (orFail(r.assetId, SurfaceRendition.decode(h, bytes)), headerSpace(hdr))
      }.toMap
      // surface id → geometry, through the manifest's declaration of which asset each surface is
      val surfaces = decls.flatMap(d => meshes.get(d.asset).map((g, _) => d.id -> (d, g))).toMap
      val spaces = decls.flatMap(d => meshes.get(d.asset).flatMap(_._2).map(d.id -> _)).toMap
      val fields = fetched.collect {
        case (r, hdr, bytes) if r.kind == "vertex-field" =>
          val h = orFail(r.assetId, VertexFieldRendition.decodeHeader(hdr))
          val surfaceId = r.surface.getOrElse(h.surface)
          val geometry = surfaces.get(surfaceId).map(_._2)
            .orElse(decls.find(_.asset == surfaceId).flatMap(d => surfaces.get(d.id)).map(_._2))
            .getOrElse(throw RuntimeException(
              s"${r.assetId}: vertex field on unknown surface '$surfaceId'"
            ))
          (r.assetId, orFail(r.assetId, VertexFieldRendition.decode(h, bytes, geometry)), h.summary)
      }
      Loaded(
        ws,
        project,
        detail,
        manifest,
        vols.map(t => t._1 -> t._2).toMap,
        (vols ++ fields).flatMap(t => t._3.map(t._1 -> _)).toMap,
        surfaces,
        fields.map(t => t._1 -> t._2).toMap,
        spaces
      )

  /** The surface-mesh header's `space` (the space its coordinates are expressed in). Optional: the
    * field is being added to the profile, and a header without it stays decodable.
    */
  def headerSpace(headerJson: String): Option[String] =
    io.circe.parser.parse(headerJson).toOption.flatMap(
      _.hcursor.downField("space").as[String].toOption
    ).map(_.trim).filter(_.nonEmpty)
