package neuropublish.protocol.json

import io.circe.{Decoder, Json}
import neuropublish.protocol.Sha256

/** The parts of a manifest the server and CLI act on. Everything else is retained as raw JSON
  * (`raw`) — unknown records are preserved, never interpreted (axiom 7). The JSON Schema
  * (`protocol/schemas/manifest.schema.json`) is the normative definition; this decoder is a
  * projection of it. It enforces the parts of the schema the projection acts on (required members,
  * the `sha256:` digest grammar, the schema-reference version grammar, non-negative sizes) so the
  * Scala.js build, which has no JSON Schema validator, rejects the same structural faults; what it
  * does not enforce is listed in SPEC §3 ("JS-lenient subset").
  */
final case class ManifestAsset(
    id: String,
    digest: Sha256,
    size: Long,
    mediaType: String,
    catalog: Option[String]
)

/** `surface`/`hemisphere` are present on `kind = "surface"` (the surface the per-vertex asset is
  * displayed on); `derivation` optionally names the provenance activity that produced the values.
  */
final case class Representation(
    kind: String,
    asset: String,
    surface: Option[String] = None,
    hemisphere: Option[String] = None,
    derivation: Option[String] = None,
    domain: Option[String] = None,
    mapping: Option[String] = None
)

final case class ResultField(
    id: String,
    label: Option[String],
    estimand: String,
    measure: String,
    domain: String,
    selection: Map[String, String],
    representations: List[Representation],
    order: Option[Int],
    publishedDisplay: Option[Json]
)

final case class Underlay(asset: String, domain: String, label: String)

/** One hemisphere's GIFTI geometry on a surface-vertices domain (SPEC §5, "Surfaces"). */
final case class Surface(
    id: String,
    asset: String,
    domain: String,
    hemisphere: String,
    kind: String,
    label: String
)

/** What the server derives for one asset: `volume` (NIfTI → `volume-f32`), `surface-mesh` (GIFTI
  * geometry → `surface-mesh`), or `vertex-field` (GIFTI scalars → `vertex-field-f32`, on
  * `surface`).
  */
final case class RenditionTarget(assetId: String, kind: String, surface: Option[String] = None)

final case class Estimand(id: String, label: String, order: Option[Int])
final case class Analysis(
    id: String,
    label: String,
    estimands: List[Estimand],
    sampleSize: Option[Int],
    method: Option[Json]
)

/** ADR 0005 domain hook: local id, open descriptor, optional exact key (retained as JSON). */
final case class Domain(id: String, descriptor: OpenRecord, key: Option[Json])

/** ADR 0005 domain relation: an exact source and target plus open mapping semantics. */
final case class DomainMapping(
    id: String,
    source: String,
    target: String,
    descriptor: OpenRecord
)

final case class Warning(id: String, message: String, concerns: Option[Json])

final case class Manifest(
    core: String,
    title: String,
    synopsis: Option[String],
    sensitivity: Option[String],
    assets: List[ManifestAsset],
    resultFields: List[ResultField],
    underlays: List[Underlay],
    surfaces: List[Surface],
    analyses: List[Analysis],
    domains: List[Domain],
    warnings: List[Warning],
    migratedFrom: Option[String],
    raw: Json,
    domainMappings: List[DomainMapping] = Nil
):
  def asset(id: String): Option[ManifestAsset] = assets.find(_.id == id)

  /** Assets that carry a volume representation (overlays) or serve as an underlay. */
  def volumeAssetIds: List[String] =
    (underlays.map(_.asset) ++
      resultFields.flatMap(_.representations.filter(_.kind == "volume").map(_.asset))).distinct

  def surface(id: String): Option[Surface] = surfaces.find(_.id == id)
  def domain(id: String): Option[Domain] = domains.find(_.id == id)
  def domainMapping(id: String): Option[DomainMapping] = domainMappings.find(_.id == id)

  /** Provenance ids from the retained open graph. */
  def provenanceIds(kind: String): List[String] =
    raw.hcursor.downField("provenance").downField(kind).as[List[Json]].toOption
      .getOrElse(Nil).flatMap(_.hcursor.get[String]("id").toOption)

  /** Surface-geometry assets, in `surfaces[]` order. */
  def surfaceAssetIds: List[String] = surfaces.map(_.asset).distinct

  /** Every asset the server derives a rendition for, in derivation order: volumes, then surface
    * geometries, then vertex fields (which need their surface's geometry first). One target per
    * asset; an asset presented twice keeps its first target.
    */
  def renditionTargets: List[RenditionTarget] =
    val volumes = volumeAssetIds.map(RenditionTarget(_, "volume"))
    val meshes = surfaceAssetIds.map(RenditionTarget(_, "surface-mesh"))
    val fields = resultFields.flatMap(_.representations.collect {
      case r if r.kind == "surface" && r.surface.isDefined =>
        RenditionTarget(r.asset, "vertex-field", r.surface)
    })
    (volumes ++ meshes ++ fields).foldLeft(List.empty[RenditionTarget]) { (acc, t) =>
      if acc.exists(_.assetId == t.assetId) then acc else acc :+ t
    }

  def renditionAssetIds: List[String] = renditionTargets.map(_.assetId)

  /** Every open record with its pointer, as the reference implementation reads it. */
  def openRecords: List[(String, OpenRecord, Interpretation)] =
    OpenRecord.collect(raw).map((p, r) => (p, r, TrustedSchemas.interpret(p, r)))

  /** Estimands in normative order: explicit `order`, ties by array position (SPEC.md). */
  def orderedEstimands(a: Analysis): List[Estimand] =
    a.estimands.zipWithIndex.sortBy((e, i) => (e.order.getOrElse(Int.MaxValue), i)).map(_._1)

  /** Result fields of one estimand in normative order. */
  def orderedFields(estimand: String): List[ResultField] =
    resultFields.zipWithIndex.filter(_._1.estimand == estimand)
      .sortBy((f, i) => (f.order.getOrElse(Int.MaxValue), i)).map(_._1)

object Manifest:
  /** Wire digests are strict: `sha256:` + 64 lowercase hex (`Sha256.parse` stays lenient for
    * internal use, where bare hex circulates).
    */
  given Decoder[Sha256] = OpenRecord.strictSha256
  private val nonNegative: Decoder[Long] =
    Decoder.decodeLong.emap(n => if n >= 0 then Right(n) else Left("must be >= 0"))
  given Decoder[ManifestAsset] = Decoder.instance { c =>
    for
      id <- c.get[String]("id")
      digest <- c.get[Sha256]("digest")
      size <- c.get[Long]("size")(using nonNegative)
      mediaType <- c.get[String]("mediaType")
      catalog <- c.get[Option[String]]("catalog")
    yield ManifestAsset(id, digest, size, mediaType, catalog)
  }
  given Decoder[Representation] =
    Decoder.forProduct7(
      "kind",
      "asset",
      "surface",
      "hemisphere",
      "derivation",
      "domain",
      "mapping"
    )(
      Representation.apply
    )
  given Decoder[ResultField] = Decoder.forProduct9(
    "id",
    "label",
    "estimand",
    "measure",
    "domain",
    "selection",
    "representations",
    "order",
    "publishedDisplay"
  )(ResultField.apply)
  given Decoder[Underlay] = Decoder.forProduct3("asset", "domain", "label")(Underlay.apply)
  given Decoder[Surface] =
    Decoder.forProduct6("id", "asset", "domain", "hemisphere", "kind", "label")(Surface.apply)
  given Decoder[Estimand] = Decoder.forProduct3("id", "label", "order")(Estimand.apply)
  given Decoder[Analysis] = Decoder.instance { c =>
    for
      id <- c.get[String]("id")
      label <- c.get[String]("label")
      estimands <- c.get[List[Estimand]]("estimands")
      sampleSize <- c.get[Option[Long]]("sampleSize")(using Decoder.decodeOption(using nonNegative))
      method <- c.get[Option[Json]]("method")
    yield Analysis(id, label, estimands, sampleSize.map(_.toInt), method)
  }
  given Decoder[Domain] = Decoder.forProduct3("id", "descriptor", "key")(Domain.apply)
  given Decoder[DomainMapping] =
    Decoder.forProduct4("id", "source", "target", "descriptor")(DomainMapping.apply)
  given Decoder[Warning] = Decoder.forProduct3("id", "message", "concerns")(Warning.apply)

  given Decoder[Manifest] = Decoder.instance { c =>
    def list[A: Decoder](field: String) =
      c.downField(field).as[Option[List[A]]].map(_.getOrElse(Nil))
    for
      core <- c.downField("core").as[String]
      title <- c.downField("title").as[String]
      synopsis <- c.downField("synopsis").as[Option[String]]
      sensitivity <- c.downField("sensitivity").as[Option[String]]
      assets <- c.downField("assets").as[List[ManifestAsset]]
      fields <- list[ResultField]("resultFields")
      underlays <- list[Underlay]("underlays")
      surfaces <- list[Surface]("surfaces")
      analyses <- list[Analysis]("analyses")
      domains <- list[Domain]("domains")
      domainMappings <- list[DomainMapping]("domainMappings")
      warnings <- list[Warning]("warnings")
      migratedFrom <- c.downField("migratedFrom").as[Option[String]]
    yield Manifest(
      core,
      title,
      synopsis,
      sensitivity,
      assets,
      fields,
      underlays,
      surfaces,
      analyses,
      domains,
      warnings,
      migratedFrom,
      c.value,
      domainMappings
    )
  }

  /** Full admission of producer bytes: byte profile (ADR 0001) → JSON → core version / migration →
    * JSON Schema (JVM only; the JS build keeps the decoder-only path) → structural decoder →
    * reference closure → semantic checks. Problems accumulate across stages; a stage that leaves
    * nothing to check (unparseable bytes, undecodable structure) ends the pipeline. The digest is
    * always over the original bytes, migrated or not.
    */
  def parse(bytes: Array[Byte]): Either[List[Problem], (Sha256, Manifest)] =
    ByteProfile.admit(bytes) match
      case Left(vs) => Left(vs.map(_.problem))
      case Right(digest) =>
        _root_.io.circe.parser.parse(new String(bytes, "UTF-8")) match
          case Left(e) => Left(List(Problem("", s"not JSON: ${e.getMessage}")))
          case Right(json) => admit(json).map(m => (digest, m))

  /** Admission from a parsed value (everything after the byte profile). */
  def admit(json: Json): Either[List[Problem], Manifest] =
    Migrations.bring(json) match
      case Left(p) => Left(List(p))
      case Right(current) =>
        // schema and raw-JSON checks run whether or not the structural decoder succeeds; where
        // both speak to one pointer the raw check's message (which names the remedy) is kept
        val raw = ManifestChecks.catalogs(current)
        val rawAt = raw.map(_.pointer).toSet
        val early = SchemaCheck.manifest(current).filterNot(p => rawAt(p.pointer)) ++ raw
        current.as[Manifest] match
          case Left(e) =>
            val at = JsonPointer.ofFailure(e)
            val decoder = Option.when(!early.exists(_.pointer == at))(Problem(at, e.message))
            Left((early ++ decoder).distinct)
          case Right(m) =>
            // a pointer the schema already reported is not reported again by a semantic check
            val reported = early.map(_.pointer).toSet
            val all = (early ++ ManifestChecks.all(m).filterNot(p => reported(p.pointer))).distinct
            if all.isEmpty then Right(m) else Left(all)

  /** Every representation, underlay, and surface must reference a declared asset. */
  def referenceClosure(m: Manifest): Either[String, Unit] =
    val ps = ManifestChecks.referenceClosure(m)
    if ps.isEmpty then Right(()) else Left(Problem.render(ps))
