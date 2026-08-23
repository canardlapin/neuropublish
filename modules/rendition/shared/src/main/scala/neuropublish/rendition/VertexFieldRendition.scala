package neuropublish.rendition

import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import scalafim.surface.{SurfaceField, SurfaceGeometry}

/** Browser-ready typed-binary rendition of one per-vertex scalar field (Stage 5 profile
  * `org.neuropublish.rendition/vertex-field-f32@0`): a JSON header naming the surface asset the
  * values are defined on and a little-endian float32 payload, one value per vertex in vertex order
  * (NaN for missing). The values are the producer's — the server decodes the GIFTI and narrows to
  * float32; it never projects a volume onto a surface (plan, Stage 5: no projection without a
  * recorded derivation receipt).
  */
final case class VertexFieldHeader(
    profile: String,
    surface: String, // the surface asset id (a `surfaces[].id`) the field is defined on
    vertexCount: Int,
    summary: Option[ScalarSummary],
    source: Option[String]
)

object VertexFieldHeader:
  val Profile = "org.neuropublish.rendition/vertex-field-f32@0"
  given Encoder[VertexFieldHeader] =
    Encoder.forProduct5("profile", "surface", "vertexCount", "summary", "source")(h =>
      (h.profile, h.surface, h.vertexCount, h.summary, h.source)
    )
  given Decoder[VertexFieldHeader] =
    Decoder.forProduct5("profile", "surface", "vertexCount", "summary", "source")(
      VertexFieldHeader.apply
    )

final case class VertexFieldRendition(header: VertexFieldHeader, payload: Array[Byte])

object VertexFieldRendition:

  /** Encode `values` (one per vertex of `surface`, whose id is recorded in the header). */
  def encode(
      surface: String,
      values: Array[Double],
      source: Option[String] = None
  ): VertexFieldRendition =
    val bytes = new Array[Byte](values.length * 4)
    var i = 0
    while i < values.length do
      LittleEndian.putInt(bytes, i * 4, java.lang.Float.floatToIntBits(values(i).toFloat))
      i += 1
    VertexFieldRendition(
      VertexFieldHeader(
        VertexFieldHeader.Profile,
        surface,
        values.length,
        Some(ScalarSummary.of(values)),
        source
      ),
      bytes
    )

  def encode(
      field: SurfaceField[Double],
      surface: String,
      source: Option[String]
  ): VertexFieldRendition =
    encode(surface, field.data, source)

  def headerJson(h: VertexFieldHeader): String = h.asJson.spaces2 + "\n"

  def decodeHeader(json: String): Either[String, VertexFieldHeader] =
    _root_.io.circe.parser.decode[VertexFieldHeader](json).left.map(_.getMessage).flatMap { h =>
      if h.profile != VertexFieldHeader.Profile then Left(s"unsupported profile ${h.profile}")
      else if h.vertexCount <= 0 then Left("vertexCount must be positive")
      else if h.surface.isEmpty then Left("surface must name the surface asset")
      else if h.vertexCount.toLong * 4L > Int.MaxValue.toLong then
        Left("field too large for a single rendition payload")
      else Right(h)
    }

  /** Rebuild the field on `geometry`, which must have exactly the header's vertex count. */
  def decode(
      header: VertexFieldHeader,
      payload: Array[Byte],
      geometry: SurfaceGeometry
  ): Either[String, SurfaceField[Double]] =
    val n = header.vertexCount
    if n <= 0 then Left("vertexCount must be positive")
    else if geometry.vertexCount != n then
      Left(s"field has $n vertices but the surface has ${geometry.vertexCount}")
    else if n.toLong * 4L > Int.MaxValue.toLong then
      Left("field too large for a single rendition payload")
    else if payload.length.toLong != n.toLong * 4L then
      Left(s"payload has ${payload.length} bytes, expected ${n.toLong * 4L}")
    else
      val values = new Array[Double](n)
      var i = 0
      while i < n do
        values(i) = java.lang.Float.intBitsToFloat(LittleEndian.getInt(payload, i * 4)).toDouble
        i += 1
      SurfaceField.fullEither(geometry, values.toSeq, header.source.getOrElse(""))
        .left.map(_.message)
