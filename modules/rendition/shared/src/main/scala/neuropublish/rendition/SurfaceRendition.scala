package neuropublish.rendition

import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import scalafim.image.DMat
import scalafim.surface.{Hemisphere, SurfaceGeometry, SurfaceKind, TriangleMesh}

/** Browser-ready typed-binary rendition of one hemisphere's surface geometry (Stage 5 profile
  * `org.neuropublish.rendition/surface-mesh@0`): a JSON header and one little-endian payload of
  * float32 vertex positions (x, y, z per vertex, vertex order) followed by int32 triangle vertex
  * ordinals (three per face, face order). Derived server-side from the canonical GIFTI asset and
  * recorded outside the manifest digest (ADR 0001, plan decision 2). `topologyIdentity` is
  * ScalaFIM's `MeshTopologyIdentity.stableKey` of the faces, so a decoder can prove it rebuilt the
  * same ordered topology; the protocol-level domain key (ADR 0005 `surface-vertices/v1`) is
  * verified at ingestion, not carried here.
  */
final case class SurfaceRenditionHeader(
    profile: String,
    hemisphere: String, // "left" | "right"
    kind: String, // pial | white | midthickness | inflated | …
    vertexCount: Int,
    faceCount: Int,
    surfaceToWorld: Vector[Vector[Double]], // 4x4, row-major rows
    coordinateSystem: String, // "RAS+"
    topologyIdentity: String, // MeshTopologyIdentity.stableKey (16 lowercase hex)
    source: Option[String]
)

object SurfaceRenditionHeader:
  val Profile = "org.neuropublish.rendition/surface-mesh@0"
  val CoordinateSystem = "RAS+"
  given Encoder[SurfaceRenditionHeader] = Encoder.forProduct9(
    "profile",
    "hemisphere",
    "kind",
    "vertexCount",
    "faceCount",
    "surfaceToWorld",
    "coordinateSystem",
    "topologyIdentity",
    "source"
  )(h =>
    (
      h.profile,
      h.hemisphere,
      h.kind,
      h.vertexCount,
      h.faceCount,
      h.surfaceToWorld,
      h.coordinateSystem,
      h.topologyIdentity,
      h.source
    )
  )
  given Decoder[SurfaceRenditionHeader] = Decoder.forProduct9(
    "profile",
    "hemisphere",
    "kind",
    "vertexCount",
    "faceCount",
    "surfaceToWorld",
    "coordinateSystem",
    "topologyIdentity",
    "source"
  )(SurfaceRenditionHeader.apply)

final case class SurfaceRendition(header: SurfaceRenditionHeader, payload: Array[Byte])

object SurfaceRendition:
  /** Payload byte length for the header's counts: 12 bytes per vertex, 12 per face. */
  def payloadLength(vertexCount: Int, faceCount: Int): Long =
    vertexCount.toLong * 12L + faceCount.toLong * 12L

  def hemisphereName(h: Hemisphere): Either[String, String] = h match
    case Hemisphere.Left => Right("left")
    case Hemisphere.Right => Right("right")
    case other => Left(s"a surface rendition needs a left or right hemisphere, not ${other.code}")

  /** Encode a geometry whose hemisphere is left or right; positions are narrowed to float32. */
  def encode(
      geometry: SurfaceGeometry,
      source: Option[String] = None
  ): Either[String, SurfaceRendition] =
    hemisphereName(geometry.hemisphere).map { hemisphere =>
      val mesh = geometry.mesh
      val v = mesh.vertexCount; val f = mesh.faceCount
      val bytes = new Array[Byte](payloadLength(v, f).toInt)
      var i = 0
      while i < v * 3 do
        LittleEndian.putInt(
          bytes,
          i * 4,
          java.lang.Float.floatToIntBits(mesh.coordinates(i).toFloat)
        )
        i += 1
      var k = 0
      val faceBase = v * 12
      while k < f * 3 do
        LittleEndian.putInt(bytes, faceBase + k * 4, mesh.faceIndices(k))
        k += 1
      val m = geometry.surfaceToWorld
      val affine = Vector.tabulate(4, 4)((r, c) => m.data(r * 4 + c))
      SurfaceRendition(
        SurfaceRenditionHeader(
          SurfaceRenditionHeader.Profile,
          hemisphere,
          geometry.kind.label,
          v,
          f,
          affine,
          SurfaceRenditionHeader.CoordinateSystem,
          mesh.topologyIdentity.stableKey,
          source
        ),
        bytes
      )
    }

  def headerJson(h: SurfaceRenditionHeader): String = h.asJson.spaces2 + "\n"

  def decodeHeader(json: String): Either[String, SurfaceRenditionHeader] =
    _root_.io.circe.parser.decode[SurfaceRenditionHeader](json).left.map(_.getMessage).flatMap {
      h =>
        if h.profile != SurfaceRenditionHeader.Profile then
          Left(s"unsupported profile ${h.profile}")
        else if h.coordinateSystem != SurfaceRenditionHeader.CoordinateSystem then
          Left(s"unsupported coordinate system ${h.coordinateSystem}")
        else if h.hemisphere != "left" && h.hemisphere != "right" then
          Left(s"hemisphere must be left or right, not '${h.hemisphere}'")
        else if h.vertexCount <= 0 || h.faceCount <= 0 then
          Left("vertexCount and faceCount must be positive")
        else if h.surfaceToWorld.length != 4 || h.surfaceToWorld.exists(_.length != 4) then
          Left("surfaceToWorld must be 4x4")
        else if payloadLength(h.vertexCount, h.faceCount) > Int.MaxValue.toLong then
          Left("surface too large for a single rendition payload")
        else if h.kind.trim.isEmpty then Left("kind must not be empty")
        else Right(h)
    }

  /** Rebuild the geometry; the faces must hash to the header's `topologyIdentity`. */
  def decode(
      header: SurfaceRenditionHeader,
      payload: Array[Byte]
  ): Either[String, SurfaceGeometry] =
    val v = header.vertexCount; val f = header.faceCount
    val expected = payloadLength(v, f)
    if v <= 0 || f <= 0 then Left("vertexCount and faceCount must be positive")
    else if payload.length.toLong != expected then
      Left(s"payload has ${payload.length} bytes, expected $expected")
    else
      val coords = new Array[Double](v * 3)
      var i = 0
      while i < coords.length do
        coords(i) = java.lang.Float.intBitsToFloat(LittleEndian.getInt(payload, i * 4)).toDouble
        i += 1
      val faces = new Array[Int](f * 3)
      val faceBase = v * 12
      var k = 0
      while k < faces.length do
        faces(k) = LittleEndian.getInt(payload, faceBase + k * 4)
        k += 1
      val hemisphere = if header.hemisphere == "left" then Hemisphere.Left else Hemisphere.Right
      for
        mesh <- scala.util.Try(TriangleMesh.fromArrays(coords, faces)).toEither.left.map(e =>
          s"invalid mesh: ${e.getMessage}"
        )
        _ <- Either.cond(
          mesh.topologyIdentity.stableKey == header.topologyIdentity,
          (),
          s"topology identity ${mesh.topologyIdentity.stableKey} does not equal the header's ${header.topologyIdentity}"
        )
        kind = SurfaceKind.fromString(header.kind)
        trans <- scala.util.Try(DMat.fromRows(header.surfaceToWorld)).toEither.left.map(e =>
          s"invalid surfaceToWorld: ${e.getMessage}"
        )
        geometry <- SurfaceGeometry.readEither(mesh, hemisphere, kind, trans).left.map(_.message)
      yield geometry

/** Little-endian int32 access on byte arrays (shared by the JVM and Scala.js decoders). */
private[rendition] object LittleEndian:
  inline def putInt(bytes: Array[Byte], o: Int, bits: Int): Unit =
    bytes(o) = (bits & 0xff).toByte
    bytes(o + 1) = ((bits >>> 8) & 0xff).toByte
    bytes(o + 2) = ((bits >>> 16) & 0xff).toByte
    bytes(o + 3) = ((bits >>> 24) & 0xff).toByte
  inline def getInt(bytes: Array[Byte], o: Int): Int = (bytes(o) & 0xff) |
    ((bytes(o + 1) & 0xff) << 8) | ((bytes(o + 2) & 0xff) << 16) |
    ((bytes(o + 3) & 0xff) << 24)
