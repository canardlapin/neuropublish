package neuropublish.rendition

import munit.FunSuite
import neuropublish.protocol.json.{SurfaceVertices, TrustedSchemas}
import scalafim.surface.{Hemisphere, SurfaceKind}
import scalafim.surface.io.{GiftiReader, GiftiSurfaceReader}

/** JVM half of the surface fidelity proof: the canonical GIFTI fixtures encode to the checked-in
  * renditions byte for byte, and the triangles of each surface hash to the ADR 0005
  * `surface-vertices/v1` key the reference manifest declares.
  */
class GiftiToRenditionSuite extends FunSuite:
  private val surfaces = List(("lh-pial", "left"), ("rh-pial", "right"))
  private val fields =
    List(
      "speech-t-lh" -> "lh-pial",
      "speech-t-rh" -> "rh-pial",
      "speech-z-lh" -> "lh-pial",
      "speech-z-rh" -> "rh-pial"
    )

  private def write(jsonRel: String, binRel: String, hdr: String, payload: Array[Byte]): Unit =
    if sys.env.contains("NP_WRITE_FIXTURES") || !FixtureIO.exists(binRel) then
      FixtureIO.writeBytes(jsonRel, hdr.getBytes("UTF-8"))
      FixtureIO.writeBytes(binRel, payload)
    assertEquals(FixtureIO.readText(jsonRel), hdr, s"$jsonRel drifted")
    assert(java.util.Arrays.equals(FixtureIO.readBytes(binRel), payload), s"$binRel drifted")

  test("GIFTI surfaces and fields encode deterministically to the committed renditions") {
    surfaces.foreach { (id, hemisphere) =>
      val g = GiftiSurfaceReader.read(
        FixtureIO.root.resolve(s"reference/assets/$id.surf.gii"),
        Hemisphere.fromString(hemisphere),
        SurfaceKind.Pial
      )
      assertEquals((g.vertexCount, g.faceCount), (642, 1280))
      val r = SurfaceRendition.encode(g, Some(s"assets/$id.surf.gii")).fold(fail(_), identity)
      write(
        s"reference/renditions/$id.json",
        s"reference/renditions/$id.bin",
        SurfaceRendition.headerJson(r.header),
        r.payload
      )
    }
    fields.foreach { (id, surface) =>
      val doc = GiftiReader.read(FixtureIO.root.resolve(s"reference/assets/$id.func.gii"))
        .fold(e => fail(e.message), identity)
      val values = GiftiReader.doubleData(doc.dataArrays.head).fold(e => fail(e.message), identity)
      assertEquals(values.length, 642)
      val r = VertexFieldRendition.encode(surface, values, Some(s"assets/$id.func.gii"))
      write(
        s"reference/renditions/$id.json",
        s"reference/renditions/$id.f32",
        VertexFieldRendition.headerJson(r.header),
        r.payload
      )
    }
  }

  test("each surface's triangles hash to the surface-vertices key in the reference manifest") {
    val manifest = _root_.io.circe.parser.parse(FixtureIO.readText("reference/manifest.json"))
      .toOption.get
    val domains = manifest.hcursor.downField("domains").values.get.toList
    surfaces.foreach { (id, hemisphere) =>
      val g = GiftiSurfaceReader.read(
        FixtureIO.root.resolve(s"reference/assets/$id.surf.gii"),
        Hemisphere.fromString(hemisphere),
        SurfaceKind.Pial
      )
      val d = domains.find(_.hcursor.get[String]("id").toOption.contains(s"ico3-${id.take(2)}")).get
      val payload = d.hcursor.downField("descriptor").downField("payload").focus.get
      val p = SurfaceVertices.readPayload("", payload).fold(ps => fail(ps.mkString("; ")), identity)
      assertEquals((p.vertexCount, p.faceCount, p.hemisphere), (642, 1280, hemisphere))
      val schema = TrustedSchemas.SurfaceVerticesV1
      assertEquals(
        SurfaceVertices.fingerprint(schema.id, schema.version, p, g.mesh.faceIndices).render,
        d.hcursor.downField("key").get[String]("structuralFingerprint").toOption.get,
        id
      )
    }
  }
