package neuropublish.protocol.json

import io.circe.{Decoder, Json}
import neuropublish.protocol.{SemanticId, Sha256}

/** Wire form of a schema reference: `digest` is optional on the wire (a record without a digest can
  * only ever be retained, never trusted).
  */
final case class SchemaRefWire(id: String, version: String, digest: Option[Sha256]):
  def render: String = s"$id@$version"

/** An open semantic record as written (ADR 0001): schema reference plus payload, retained whole. */
final case class OpenRecord(schema: SchemaRefWire, payload: Json)

object OpenRecord:
  /** The wire grammar of a digest (`manifest.schema.json#/$defs/sha256`): the prefix is mandatory.
    */
  val strictSha256: Decoder[Sha256] = Decoder.decodeString.emap { s =>
    if s.startsWith("sha256:") then Sha256.parse(s)
    else Left(s"not a sha256 identity: '$s' (expected sha256:<64 lowercase hex>)")
  }
  private val Version = "^[0-9]+(\\.[0-9]+)*$".r
  given Decoder[Sha256] = strictSha256
  given Decoder[SchemaRefWire] = Decoder.instance { c =>
    for
      id <- c.get[String]("id")
      version <- c.get[String]("version").flatMap(v =>
        if Version.matches(v) then Right(v)
        else
          Left(io.circe.DecodingFailure(
            s"schema version '$v' is not dotted digits (for example 1.0)",
            c.downField("version").history
          ))
      )
      digest <- c.get[Option[Sha256]]("digest")
    yield SchemaRefWire(id, version, digest)
  }
  given Decoder[OpenRecord] = Decoder.instance { c =>
    for
      schema <- c.downField("schema").as[SchemaRefWire]
      payload <- c.downField("payload").as[Option[Json]]
    yield OpenRecord(schema, payload.getOrElse(Json.obj()))
  }

  /** Every open record in a manifest with its JSON Pointer: domain descriptors, analysis methods,
    * provenance activities.
    */
  def collect(manifest: Json): List[(String, OpenRecord)] =
    val c = manifest.hcursor
    def at(pointer: String, j: Json): Option[(String, OpenRecord)] =
      j.as[OpenRecord].toOption.map(pointer -> _)
    def items(field: String): List[(Int, Json)] =
      c.downField(field).as[List[Json]].toOption.getOrElse(Nil).zipWithIndex.map(_.swap)
    val domains = items("domains").flatMap { (i, d) =>
      d.hcursor.downField("descriptor").focus.flatMap(at(s"/domains/$i/descriptor", _))
    }
    val methods = items("analyses").flatMap { (i, a) =>
      a.hcursor.downField("method").focus.flatMap(at(s"/analyses/$i/method", _))
    }
    val activities = c.downField("provenance").downField("activities").as[List[Json]].toOption
      .getOrElse(Nil).zipWithIndex.flatMap((a, i) => at(s"/provenance/activities/$i", a))
    domains ++ methods ++ activities

/** How the reference implementation reads an open record (architecture, "Open semantic records").
  */
enum Interpretation:
  /** A trusted schema the implementation understands; `note` is the typed summary it offers. */
  case Understood(record: OpenRecord, note: String)

  /** Retained, shown generically, grants no behavior. */
  case Unsupported(record: OpenRecord)

  /** Claims a trusted id but cannot be trusted (digest or version mismatch). */
  case Invalid(record: OpenRecord, problems: List[Problem])

/** The small built-in list of trusted record schemas: (id, version) to the schema document digest.
  * Everything under `org.neuropublish.*` is owned here; a record claiming one of these ids with a
  * digest or version this build does not know is rejected, never silently admitted.
  */
object TrustedSchemas:
  val TrustedNamespace = "org.neuropublish"

  /** `protocol/schemas/records/volume-grid-v1.schema.json` (ADR 0005 volume-grid/v1). */
  val VolumeGridV1 = SchemaRefWire(
    "org.neuropublish.domain/volume-grid",
    "1.0",
    Some(Sha256.unsafe("69c25b8868349828e41cd6d610ac619af118fb7b807b7306f706b727ed23dfb7"))
  )

  /** `protocol/schemas/records/surface-vertices-v1.schema.json` (ADR 0005 surface-vertices/v1). */
  val SurfaceVerticesV1 = SchemaRefWire(
    "org.neuropublish.domain/surface-vertices",
    "1.0",
    Some(Sha256.unsafe("2adc4285db8d257af4aa4e54272631451b7103d1a124b83544dc0814f85487e8"))
  )

  val all: List[SchemaRefWire] = List(VolumeGridV1, SurfaceVerticesV1)
  private val byId: Map[String, List[SchemaRefWire]] = all.groupBy(_.id)

  def isTrustedNamespace(id: String): Boolean =
    SemanticId.parse(id).toOption.exists(ns =>
      ns.namespace == TrustedNamespace || ns.namespace.startsWith(TrustedNamespace + ".")
    )

  /** Interpret one record; `pointer` addresses it for problems. */
  def interpret(pointer: String, r: OpenRecord): Interpretation =
    byId.get(r.schema.id) match
      case Some(known) =>
        known.find(_.version == r.schema.version) match
          case None =>
            Interpretation.Invalid(
              r,
              List(Problem(
                s"$pointer/schema/version",
                s"trusted schema ${r.schema.id} has no version ${r.schema.version} in this build (known: ${known.map(_.version).mkString(", ")})"
              ))
            )
          case Some(t) if r.schema.digest.map(_.hex) == t.digest.map(_.hex) =>
            Interpretation.Understood(r, s"${t.render}: trusted ${describe(t)}")
          case Some(t) =>
            Interpretation.Invalid(
              r,
              List(Problem(
                s"$pointer/schema/digest",
                s"schema digest ${r.schema.digest.map(_.render).getOrElse("(absent)")} does not match trusted ${t.render} (${t.digest.map(_.render).getOrElse("")})"
              ))
            )
      case None if isTrustedNamespace(r.schema.id) =>
        Interpretation.Invalid(
          r,
          List(Problem(
            s"$pointer/schema/id",
            s"${r.schema.id} is in the trusted namespace but is not a schema this build knows"
          ))
        )
      case None => Interpretation.Unsupported(r)

  private def describe(t: SchemaRefWire): String =
    if t == VolumeGridV1 then "volume grid descriptor (shape, affine, space); renderable"
    else if t == SurfaceVerticesV1 then
      "surface vertex domain (hemisphere, counts, topology asset); renderable on its surfaces"
    else "record"
