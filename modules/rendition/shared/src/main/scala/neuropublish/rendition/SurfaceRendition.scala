package neuropublish.rendition

import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import neuropublish.protocol.Sha256
import scalafim.image.DMat
import scalafim.surface.{Hemisphere, SurfaceGeometry, SurfaceKind, TriangleMesh}

/** The GIFTI `CoordinateSystemTransformMatrix` ingestion applied to the positions before writing
  * them (provenance only: the payload is already in world space and the header's `surfaceToWorld`
  * is the identity). `None` on a rendition whose source carried no transform.
  */
final case class SourceTransform(
    matrix: Vector[Vector[Double]], // 4x4, row-major rows
    dataSpace: Option[String], // NIFTI_XFORM_* as written
    transformedSpace: Option[String]
)

object SourceTransform:
  given Encoder[SourceTransform] =
    Encoder.forProduct3("matrix", "dataSpace", "transformedSpace")(t =>
      (t.matrix, t.dataSpace, t.transformedSpace)
    )
  given Decoder[SourceTransform] =
    Decoder.forProduct3("matrix", "dataSpace", "transformedSpace")(SourceTransform.apply)

/** Browser-ready typed-binary rendition of one hemisphere's surface geometry (Stage 5 profile
  * `org.neuropublish.rendition/surface-mesh@0`): a JSON header and one little-endian payload of
  * float32 vertex positions (x, y, z per vertex, vertex order) followed by int32 triangle vertex
  * ordinals (three per face, face order). Derived server-side from the canonical GIFTI asset and
  * recorded outside the manifest digest (ADR 0001, plan decision 2).
  *
  * Positions are world coordinates (RAS+ mm): ingestion applies the GIFTI's coordinate transform
  * before encoding (SPEC §5, "Surfaces"), so `surfaceToWorld` is the identity and `sourceTransform`
  * records what was applied. `space` is the surface-vertices domain's space id, so a reader can
  * refuse to link a surface with a volume of another space. `topologyIdentity` is ScalaFIM's
  * `MeshTopologyIdentity.stableKey` of the faces (the reference implementation's key; a seeded
  * non-cryptographic hash); `faceDigest` is the SHA-256 of the payload's face bytes (little-endian
  * int32 ordinals in face order), the stable identity a decoder proves. The protocol-level domain
  * key (ADR 0005 `surface-vertices/v1`) is verified at ingestion, not carried here.
  */
final case class SurfaceRenditionHeader(
    profile: String,
    hemisphere: String, // "left" | "right"
    kind: String, // pial | white | midthickness | inflated | …
    space: String, // the surface-vertices domain's space id
    vertexCount: Int,
    faceCount: Int,
    surfaceToWorld: Vector[Vector[Double]], // 4x4, row-major rows; identity for world positions
    coordinateSystem: String, // "RAS+"
    topologyIdentity: String, // MeshTopologyIdentity.stableKey (16 lowercase hex)
    faceDigest: Option[String], // sha256:<hex> of the little-endian face bytes
    sourceTransform: Option[SourceTransform],
    anatomicalStructurePrimary: Option[String], // the GIFTI's, as written
    source: Option[String]
)

object SurfaceRenditionHeader:
  val Profile = "org.neuropublish.rendition/surface-mesh@0"
  val CoordinateSystem = "RAS+"
  given Encoder[SurfaceRenditionHeader] = Encoder.forProduct13(
    "profile",
    "hemisphere",
    "kind",
    "space",
    "vertexCount",
    "faceCount",
    "surfaceToWorld",
    "coordinateSystem",
    "topologyIdentity",
    "faceDigest",
    "sourceTransform",
    "anatomicalStructurePrimary",
    "source"
  )(h =>
    (
      h.profile,
      h.hemisphere,
      h.kind,
      h.space,
      h.vertexCount,
      h.faceCount,
      h.surfaceToWorld,
      h.coordinateSystem,
      h.topologyIdentity,
      h.faceDigest,
      h.sourceTransform,
      h.anatomicalStructurePrimary,
      h.source
    )
  )
  given Decoder[SurfaceRenditionHeader] = Decoder.forProduct13(
    "profile",
    "hemisphere",
    "kind",
    "space",
    "vertexCount",
    "faceCount",
    "surfaceToWorld",
    "coordinateSystem",
    "topologyIdentity",
    "faceDigest",
    "sourceTransform",
    "anatomicalStructurePrimary",
    "source"
  )(SurfaceRenditionHeader.apply)

final case class SurfaceRendition(header: SurfaceRenditionHeader, payload: Array[Byte])

object SurfaceRendition:
  /** Payload byte length for the header's counts: 12 bytes per vertex, 12 per face. */
  def payloadLength(vertexCount: Int, faceCount: Int): Long =
    vertexCount.toLong * 12L + faceCount.toLong * 12L

  val Identity: Vector[Vector[Double]] =
    Vector.tabulate(4, 4)((r, c) => if r == c then 1.0 else 0.0)

  def hemisphereName(h: Hemisphere): Either[String, String] = h match
    case Hemisphere.Left => Right("left")
    case Hemisphere.Right => Right("right")
    case other => Left(s"a surface rendition needs a left or right hemisphere, not ${other.code}")

  /** The little-endian int32 face bytes of `faceIndices` (the payload's face section). */
  def faceBytes(faceIndices: Array[Int]): Array[Byte] =
    val bytes = new Array[Byte](faceIndices.length * 4)
    var k = 0
    while k < faceIndices.length do
      LittleEndian.putInt(bytes, k * 4, faceIndices(k))
      k += 1
    bytes

  /** SHA-256 of the face bytes: the stable topology identity (SPEC §5, "Renditions"). */
  def faceDigest(faceIndices: Array[Int]): String = Sha256.of(faceBytes(faceIndices)).render

  /** Encode a geometry whose hemisphere is left or right. The geometry's positions must already
    * be world coordinates: its `surfaceToWorld` must be the identity (ingestion applies the
    * source transform and records it as `sourceTransform`), so a reader never applies a transform
    * twice. Positions are narrowed to float32.
    */
  def encode(
      geometry: SurfaceGeometry,
      space: String,
      source: Option[String] = None,
      sourceTransform: Option[SourceTransform] = None,
      anatomicalStructurePrimary: Option[String] = None
  ): Either[String, SurfaceRendition] =
    val m = geometry.surfaceToWorld
    val affine = Vector.tabulate(4, 4)((r, c) => m.data(r * 4 + c))
    for
      hemisphere <- hemisphereName(geometry.hemisphere)
      _ <- Either.cond(
        affine == Identity,
        (),
        "a surface rendition takes world positions: apply the geometry's surfaceToWorld first"
      )
      _ <- Either.cond(space.trim.nonEmpty, (), "space must not be empty")
      _ <- Either.cond(
        payloadLength(geometry.vertexCount, geometry.faceCount) <= Int.MaxValue.toLong,
        (),
        "surface too large for a single rendition payload"
      )
    yield
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
      val faces = faceBytes(mesh.faceIndices)
      System.arraycopy(faces, 0, bytes, v * 12, faces.length)
      SurfaceRendition(
        SurfaceRenditionHeader(
          SurfaceRenditionHeader.Profile,
          hemisphere,
          geometry.kind.label,
          space,
          v,
          f,
          Identity,
          SurfaceRenditionHeader.CoordinateSystem,
          mesh.topologyIdentity.stableKey,
          Some(Sha256.of(faces).render),
          sourceTransform,
          anatomicalStructurePrimary,
          source
        ),
        bytes
      )

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
        else if h.sourceTransform.exists(t => t.matrix.length != 4 || t.matrix.exists(_.length != 4))
        then Left("sourceTransform.matrix must be 4x4")
        else if payloadLength(h.vertexCount, h.faceCount) > Int.MaxValue.toLong then
          Left("surface too large for a single rendition payload")
        else if h.kind.trim.isEmpty then Left("kind must not be empty")
        else if h.space.trim.isEmpty then Left("space must not be empty")
        else if h.faceDigest.exists(d => !d.startsWith("sha256:") || Sha256.parse(d).isLeft) then
          Left(s"faceDigest '${h.faceDigest.get}' is not a sha256 identity")
        else Right(h)
    }

  /** Rebuild the geometry; the faces must hash to the header's `topologyIdentity` and, when the
    * header carries one, to its `faceDigest`.
    */
  def decode(
      header: SurfaceRenditionHeader,
      payload: Array[Byte]
  ): Either[String, SurfaceGeometry] =
    val v = header.vertexCount; val f = header.faceCount
    val expected = payloadLength(v, f)
    if v <= 0 || f <= 0 then Left("vertexCount and faceCount must be positive")
    else if expected > Int.MaxValue.toLong then
      Left("surface too large for a single rendition payload")
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
        _ <- header.faceDigest match
          case None => Right(())
          case Some(declared) =>
            val actual = Sha256.of(java.util.Arrays.copyOfRange(payload, faceBase, payload.length))
            Either.cond(
              Sha256.parse(declared).map(_.hex) == Right(actual.hex),
              (),
              s"face digest ${actual.render} does not equal the header's $declared"
            )
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
