package neuropublish.protocol.json

import io.circe.{Decoder, Json}
import neuropublish.protocol.Sha256

/** The parts of a manifest the server and CLI act on. Everything else is retained as raw JSON
  * (`raw`) — unknown records are preserved, never interpreted (axiom 7). The JSON Schema
  * (`protocol/schemas/manifest.schema.json`) is the normative definition; this decoder is a
  * projection of it and is deliberately lenient (it also decodes the shared presentation subset,
  * which omits sensitivity and provenance).
  */
final case class ManifestAsset(
    id: String,
    digest: Sha256,
    size: Long,
    mediaType: String,
    catalog: Option[String]
)

final case class Representation(kind: String, asset: String)

final case class ResultField(
    id: String,
    estimand: String,
    measure: String,
    domain: String,
    representations: List[Representation],
    order: Option[Int],
    publishedDisplay: Option[Json]
)

final case class Underlay(asset: String, domain: String, label: String)

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

final case class Warning(id: String, message: String, concerns: Option[Json])

final case class Manifest(
    core: String,
    title: String,
    synopsis: Option[String],
    sensitivity: Option[String],
    assets: List[ManifestAsset],
    resultFields: List[ResultField],
    underlays: List[Underlay],
    analyses: List[Analysis],
    domains: List[Domain],
    warnings: List[Warning],
    migratedFrom: Option[String],
    raw: Json
):
  def asset(id: String): Option[ManifestAsset] = assets.find(_.id == id)

  /** Assets that carry a volume representation (overlays) or serve as an underlay. */
  def volumeAssetIds: List[String] =
    (underlays.map(_.asset) ++
      resultFields.flatMap(_.representations.filter(_.kind == "volume").map(_.asset))).distinct

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
  given Decoder[Sha256] = Decoder.decodeString.emap(Sha256.parse)
  given Decoder[ManifestAsset] =
    Decoder.forProduct5("id", "digest", "size", "mediaType", "catalog")(ManifestAsset.apply)
  given Decoder[Representation] = Decoder.forProduct2("kind", "asset")(Representation.apply)
  given Decoder[ResultField] = Decoder.forProduct7(
    "id",
    "estimand",
    "measure",
    "domain",
    "representations",
    "order",
    "publishedDisplay"
  )(ResultField.apply)
  given Decoder[Underlay] = Decoder.forProduct3("asset", "domain", "label")(Underlay.apply)
  given Decoder[Estimand] = Decoder.forProduct3("id", "label", "order")(Estimand.apply)
  given Decoder[Analysis] =
    Decoder.forProduct5("id", "label", "estimands", "sampleSize", "method")(Analysis.apply)
  given Decoder[Domain] = Decoder.forProduct3("id", "descriptor", "key")(Domain.apply)
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
      analyses <- list[Analysis]("analyses")
      domains <- list[Domain]("domains")
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
      analyses,
      domains,
      warnings,
      migratedFrom,
      c.value
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
        // schema and raw-JSON checks run whether or not the structural decoder succeeds
        val early = SchemaCheck.manifest(current) ++ ManifestChecks.catalogs(current)
        current.as[Manifest] match
          case Left(e) =>
            val at = JsonPointer.ofFailure(e)
            val decoder = Option.when(!early.exists(_.pointer == at))(Problem(at, e.message))
            Left((early ++ decoder).distinct)
          case Right(m) =>
            val all = (early ++ ManifestChecks.all(m)).distinct
            if all.isEmpty then Right(m) else Left(all)

  /** Every representation and underlay must reference a declared asset. */
  def referenceClosure(m: Manifest): Either[String, Unit] =
    val ps = ManifestChecks.referenceClosure(m)
    if ps.isEmpty then Right(()) else Left(Problem.render(ps))
