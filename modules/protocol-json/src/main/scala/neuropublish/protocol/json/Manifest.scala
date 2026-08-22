package neuropublish.protocol.json

import io.circe.{Decoder, Json}
import neuropublish.protocol.Sha256

/** The parts of a manifest the server and CLI act on in Stage 1. Everything else is retained as raw
  * JSON (`raw`) — unknown records are preserved, never interpreted (axiom 7). The JSON Schema is
  * the normative definition; this decoder is a projection of it.
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

final case class Manifest(
    core: String,
    title: String,
    synopsis: Option[String],
    assets: List[ManifestAsset],
    resultFields: List[ResultField],
    underlays: List[Underlay],
    warnings: List[Json],
    raw: Json
):
  def asset(id: String): Option[ManifestAsset] = assets.find(_.id == id)

  /** Assets that carry a volume representation (overlays) or serve as an underlay. */
  def volumeAssetIds: List[String] =
    (underlays.map(_.asset) ++
      resultFields.flatMap(_.representations.filter(_.kind == "volume").map(_.asset))).distinct

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

  given Decoder[Manifest] = Decoder.instance { c =>
    for
      core <- c.downField("core").as[String]
      title <- c.downField("title").as[String]
      synopsis <- c.downField("synopsis").as[Option[String]]
      assets <- c.downField("assets").as[List[ManifestAsset]]
      fields <- c.downField("resultFields").as[Option[List[ResultField]]].map(_.getOrElse(Nil))
      underlays <- c.downField("underlays").as[Option[List[Underlay]]].map(_.getOrElse(Nil))
      warnings <- c.downField("warnings").as[Option[List[Json]]].map(_.getOrElse(Nil))
    yield Manifest(core, title, synopsis, assets, fields, underlays, warnings, c.value)
  }

  /** Admit the bytes (ADR 0001), then decode. Returns the digest with the manifest. */
  def parse(bytes: Array[Byte]): Either[String, (Sha256, Manifest)] =
    for
      digest <- ByteProfile.admit(bytes).left.map(vs => vs.map(_.render).mkString("; "))
      json <- _root_.io.circe.parser.parse(new String(bytes, "UTF-8")).left.map(_.getMessage)
      m <- json.as[Manifest].left.map(_.getMessage)
      _ <- referenceClosure(m)
    yield (digest, m)

  /** Every representation and underlay must reference a declared asset. */
  def referenceClosure(m: Manifest): Either[String, Unit] =
    val ids = m.assets.map(_.id).toSet
    val dangling =
      (m.underlays.map(_.asset) ++
        m.resultFields.flatMap(_.representations.map(_.asset))).filterNot(ids)
    if dangling.isEmpty then Right(())
    else Left(s"representations reference undeclared assets: ${dangling.mkString(", ")}")
