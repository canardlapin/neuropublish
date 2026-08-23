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
  given Decoder[Sha256] = Decoder.decodeString.emap(Sha256.parse)
  given Decoder[SchemaRefWire] =
    Decoder.forProduct3("id", "version", "digest")(SchemaRefWire.apply)
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
    Some(Sha256.unsafe("c1871091d7dc2bf6c5d3b1acafdf2d9c0d47e62d5a737a571ed7433ba778b7ac"))
  )

  val all: List[SchemaRefWire] = List(VolumeGridV1)
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
    else "record"
