// STUB until rendition track merges
//
// The Stage 5 rendition track delivers `SurfaceRendition` and `VertexFieldRendition` in
// modules/rendition with exactly these signatures. This file lets the frontend compile and run
// against them before the merge; delete it when modules/rendition carries the real decoders.
// The payload layout assumed here (float32 LE vertex xyz, then uint32 LE face indices; float32 LE
// per-vertex values) is a placeholder, not the contract.
package neuropublish.rendition

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import java.nio.{ByteBuffer, ByteOrder}
import scalafim.image.DMat
import scalafim.surface.{Hemisphere, SurfaceField, SurfaceGeometry, SurfaceKind, TriangleMesh}

final case class SurfaceRenditionHeader(
    hemisphere: String,
    kind: String,
    vertexCount: Int,
    faceCount: Int,
    surfaceToWorld: Vector[Vector[Double]],
    topologyIdentity: String,
    source: Option[String]
)

object SurfaceRendition:
  private given Decoder[SurfaceRenditionHeader] = deriveDecoder

  def decodeHeader(json: String): Either[String, SurfaceRenditionHeader] =
    _root_.io.circe.parser.decode[SurfaceRenditionHeader](json).left.map(_.getMessage).flatMap {
      h =>
        if h.vertexCount < 0 || h.faceCount < 0 then Left("negative counts")
        else if h.surfaceToWorld.length != 4 || h.surfaceToWorld.exists(_.length != 4) then
          Left("surfaceToWorld must be 4x4")
        else Right(h)
    }

  def decode(h: SurfaceRenditionHeader, bytes: Array[Byte]): Either[String, SurfaceGeometry] =
    val need = h.vertexCount.toLong * 12 + h.faceCount.toLong * 12
    if bytes.length < need then Left(s"surface payload has ${bytes.length} bytes, need $need")
    else
      val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val coords = new Array[Double](h.vertexCount * 3)
      var i = 0
      while i < coords.length do
        coords(i) = bb.getFloat().toDouble
        i += 1
      val faces = new Array[Int](h.faceCount * 3)
      i = 0
      while i < faces.length do
        faces(i) = bb.getInt()
        i += 1
      val hemi = h.hemisphere.toLowerCase match
        case "left" | "lh" | "l" => Hemisphere.Left
        case "right" | "rh" | "r" => Hemisphere.Right
        case "both" => Hemisphere.Both
        case _ => Hemisphere.Unknown
      val kind = h.kind.toLowerCase match
        case "pial" => SurfaceKind.Pial
        case "white" => SurfaceKind.White
        case "inflated" => SurfaceKind.Inflated
        case "sphere" => SurfaceKind.Sphere
        case "midthickness" => SurfaceKind.Midthickness
        case "smoothwm" => SurfaceKind.SmoothWm
        case other => SurfaceKind.Custom(other)
      scala.util.Try(
        SurfaceGeometry(
          TriangleMesh.fromArrays(coords, faces),
          hemi,
          kind,
          DMat.fromRows(h.surfaceToWorld)
        )
      ).toEither.left.map(e => s"surface geometry: ${e.getMessage}")

final case class VertexFieldHeader(
    surface: String,
    vertexCount: Int,
    summary: Option[ScalarSummary]
)

object VertexFieldRendition:
  private given Decoder[ScalarSummary] = deriveDecoder
  private given Decoder[VertexFieldHeader] = deriveDecoder

  def decodeHeader(json: String): Either[String, VertexFieldHeader] =
    _root_.io.circe.parser.decode[VertexFieldHeader](json).left.map(_.getMessage)

  def decode(
      h: VertexFieldHeader,
      bytes: Array[Byte],
      geometry: SurfaceGeometry
  ): Either[String, SurfaceField[Double]] =
    if h.vertexCount != geometry.vertexCount then
      Left(
        s"vertex field has ${h.vertexCount} vertices; surface ${h.surface} has ${geometry.vertexCount}"
      )
    else if bytes.length < h.vertexCount.toLong * 4 then
      Left(s"vertex field payload has ${bytes.length} bytes, need ${h.vertexCount * 4}")
    else
      val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val values = new Array[Double](h.vertexCount)
      var i = 0
      while i < values.length do
        values(i) = bb.getFloat().toDouble
        i += 1
      SurfaceField.fullEither(geometry, values.toIndexedSeq).left.map(_.message)
