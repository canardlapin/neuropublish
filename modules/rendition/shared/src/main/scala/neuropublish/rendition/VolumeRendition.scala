package neuropublish.rendition

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import scalafim.image.*

/** Browser-ready typed-binary rendition of one scalar volume (Stage 0 profile): a JSON header and a
  * little-endian float32 payload in x-fastest (Fortran) order, matching NIfTI. Missing values are
  * NaN. Derived server-side from the canonical asset and recorded outside the manifest digest (ADR
  * 0001, plan decision 2). Zarr remains a candidate container for larger volumes.
  */
final case class VolumeRenditionHeader(
    profile: String,
    shape: Vector[Int],
    affine: Vector[Vector[Double]],
    dtype: String,
    byteOrder: String,
    order: String,
    missing: String,
    source: Option[String]
)

object VolumeRenditionHeader:
  val Profile = "org.neuropublish.rendition/volume-f32@0"
  given Encoder[VolumeRenditionHeader] = Encoder.forProduct8(
    "profile",
    "shape",
    "affine",
    "dtype",
    "byteOrder",
    "order",
    "missing",
    "source"
  )(h => (h.profile, h.shape, h.affine, h.dtype, h.byteOrder, h.order, h.missing, h.source))
  given Decoder[VolumeRenditionHeader] = Decoder.forProduct8(
    "profile",
    "shape",
    "affine",
    "dtype",
    "byteOrder",
    "order",
    "missing",
    "source"
  )(VolumeRenditionHeader.apply)

final case class VolumeRendition(header: VolumeRenditionHeader, payload: Array[Byte])

object VolumeRendition:

  def encode(volume: NeuroVol[Double], source: Option[String] = None): VolumeRendition =
    val space = volume.space
    val dims = space.spatialDims
    val m = space.affine3D.matrix
    val affine = Vector.tabulate(4, 4)((r, c) => m.data(r * 4 + c))
    val n = dims.product
    val bytes = new Array[Byte](n * 4)
    var i = 0
    while i < n do
      val bits = java.lang.Float.floatToIntBits(volume.linear(i).toFloat)
      val o = i * 4
      bytes(o) = (bits & 0xff).toByte
      bytes(o + 1) = ((bits >>> 8) & 0xff).toByte
      bytes(o + 2) = ((bits >>> 16) & 0xff).toByte
      bytes(o + 3) = ((bits >>> 24) & 0xff).toByte
      i += 1
    VolumeRendition(
      VolumeRenditionHeader(
        VolumeRenditionHeader.Profile,
        dims,
        affine,
        "float32",
        "little",
        "x-fastest",
        "nan",
        source
      ),
      bytes
    )

  def headerJson(h: VolumeRenditionHeader): String = h.asJson.spaces2 + "\n"

  def decodeHeader(json: String): Either[String, VolumeRenditionHeader] =
    _root_.io.circe.parser.decode[VolumeRenditionHeader](json).left.map(_.getMessage).flatMap { h =>
      if h.profile != VolumeRenditionHeader.Profile then Left(s"unsupported profile ${h.profile}")
      else if h.dtype != "float32" || h.byteOrder != "little" || h.order != "x-fastest" then
        Left("unsupported encoding")
      else if h.shape.length != 3 || h.affine.length != 4 || h.affine.exists(_.length != 4) then
        Left("shape must be 3-D and affine 4x4")
      else Right(h)
    }

  def decode(
      header: VolumeRenditionHeader,
      payload: Array[Byte]
  ): Either[String, NeuroVol[Double]] =
    val n = header.shape.product
    if payload.length != n * 4 then Left(s"payload has ${payload.length} bytes, expected ${n * 4}")
    else
      val values = new Array[Double](n)
      var i = 0
      while i < n do
        val o = i * 4
        val bits = (payload(o) & 0xff) | ((payload(o + 1) & 0xff) << 8) |
          ((payload(o + 2) & 0xff) << 16) | ((payload(o + 3) & 0xff) << 24)
        values(i) = java.lang.Float.intBitsToFloat(bits).toDouble
        i += 1
      val trans = DMat.fromRows(header.affine)
      for
        space <- NeuroSpace.make(header.shape, trans = Some(trans)).left.map(_.toString)
        vol <- NeuroVol.fromLinearChecked(values, space).left.map(_.toString)
      yield vol
