package neuropublish.rendition

import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import scalafim.image.*

/** Browser-ready typed-binary rendition of one scalar volume (Stage 0 profile): a JSON header and a
  * little-endian float32 payload in x-fastest (Fortran) order, matching NIfTI. Missing values are
  * NaN. Derived server-side from the canonical asset and recorded outside the manifest digest (ADR
  * 0001, plan decision 2). Zarr remains a candidate container for larger volumes.
  */
/** Server-derived descriptive summary (plan decision 2): never part of the manifest digest. */
final case class ScalarSummary(
    min: Double,
    max: Double,
    quantiles: Vector[Double], // 1, 5, 25, 50, 75, 95, 99 %
    histogram: Vector[Int], // 32 equal-width bins over [min, max]
    finite: Int,
    missing: Int,
    zero: Int
)

object ScalarSummary:
  val QuantileProbs: Vector[Double] = Vector(0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99)
  val Bins = 32
  given Encoder[ScalarSummary] =
    Encoder.forProduct7("min", "max", "quantiles", "histogram", "finite", "missing", "zero")(s =>
      (s.min, s.max, s.quantiles, s.histogram, s.finite, s.missing, s.zero)
    )
  given Decoder[ScalarSummary] = Decoder.forProduct7(
    "min",
    "max",
    "quantiles",
    "histogram",
    "finite",
    "missing",
    "zero"
  )(ScalarSummary.apply)

  def of(values: Array[Double]): ScalarSummary =
    val finite = values.filter(v => !v.isNaN && !v.isInfinite)
    val missing = values.length - finite.length
    if finite.isEmpty then
      ScalarSummary(
        0.0,
        0.0,
        Vector.fill(QuantileProbs.length)(0.0),
        Vector.fill(Bins)(0),
        0,
        missing,
        0
      )
    else
      val sorted = finite.sorted
      val lo = sorted.head; val hi = sorted.last
      val q = QuantileProbs.map(p =>
        sorted(math.min(sorted.length - 1, math.max(0, math.round(p * (sorted.length - 1)).toInt)))
      )
      val hist = Array.fill(Bins)(0)
      val width = if hi > lo then (hi - lo) / Bins else 1.0
      finite.foreach { v =>
        val b = math.min(Bins - 1, ((v - lo) / width).toInt); hist(b) += 1
      }
      ScalarSummary(lo, hi, q, hist.toVector, finite.length, missing, finite.count(_ == 0.0))

final case class VolumeRenditionHeader(
    profile: String,
    shape: Vector[Int],
    affine: Vector[Vector[Double]],
    dtype: String,
    byteOrder: String,
    order: String,
    missing: String,
    source: Option[String],
    summary: Option[ScalarSummary] = None
)

object VolumeRenditionHeader:
  val Profile = "org.neuropublish.rendition/volume-f32@0"
  given Encoder[VolumeRenditionHeader] = Encoder.forProduct9(
    "profile",
    "shape",
    "affine",
    "dtype",
    "byteOrder",
    "order",
    "missing",
    "source",
    "summary"
  )(h =>
    (h.profile, h.shape, h.affine, h.dtype, h.byteOrder, h.order, h.missing, h.source, h.summary)
  )
  given Decoder[VolumeRenditionHeader] = Decoder.forProduct9(
    "profile",
    "shape",
    "affine",
    "dtype",
    "byteOrder",
    "order",
    "missing",
    "source",
    "summary"
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
    val values = new Array[Double](n)
    var i = 0
    while i < n do
      val v = volume.linear(i)
      values(i) = v
      val bits = java.lang.Float.floatToIntBits(v.toFloat)
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
        source,
        Some(ScalarSummary.of(values))
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
      else if h.shape.exists(_ <= 0) then Left("shape entries must be positive")
      else if h.shape.map(_.toLong).product * 4L > Int.MaxValue.toLong then
        Left("volume too large for a single rendition payload")
      else Right(h)
    }

  def decode(
      header: VolumeRenditionHeader,
      payload: Array[Byte]
  ): Either[String, NeuroVol[Double]] =
    val n = header.shape.product // validated positive and bounded by decodeHeader
    if header.shape.exists(_ <= 0) then Left("shape entries must be positive")
    else if payload.length != n * 4 then
      Left(s"payload has ${payload.length} bytes, expected ${n * 4}")
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
