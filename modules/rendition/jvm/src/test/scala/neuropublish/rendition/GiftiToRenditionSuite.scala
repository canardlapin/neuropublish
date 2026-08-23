package neuropublish.rendition

import munit.FunSuite
import neuropublish.protocol.json.{SurfaceVertices, TrustedSchemas}
import scalafim.surface.{Hemisphere, SurfaceKind}
import scalafim.surface.io.{GiftiReader, GiftiSurfaceReader}

/** JVM half of the surface fidelity proof: the canonical GIFTI fixtures encode to the checked-in
  * renditions byte for byte, and the triangles of each surface hash to the ADR 0005
  * `surface-vertices/v1` key the reference manifest declares.
  *
  * The renditions written here are what ingestion writes for the same assets (`Derivation`): the
  * fixtures' GIFTI transform is the identity into `NIFTI_XFORM_SCANNER_ANAT`, so the positions are
  * already world positions and the header's `surfaceToWorld` is the identity with the source
  * transform kept as provenance. `source` is the only difference — the server records the asset
  * digest, these fixtures the bundle-relative path.
  */
class GiftiToRenditionSuite extends FunSuite:
  private val manifest = _root_.io.circe.parser.parse(FixtureIO.readText("reference/manifest.json"))
    .toOption.get
  private val domains = manifest.hcursor.downField("domains").values.get.toList

  /** `(asset, hemisphere, surfaces[].id, domain id)` of every declared surface. */
  private val surfaces: List[(String, String, String, String)] =
    manifest.hcursor.downField("surfaces").values.get.toList.map { s =>
      val c = s.hcursor
      (
        c.get[String]("asset").toOption.get,
        c.get[String]("hemisphere").toOption.get,
        c.get[String]("id").toOption.get,
        c.get[String]("domain").toOption.get
      )
    }
  private val surfaceIdOf: Map[String, String] = surfaces.map((a, _, id, _) => a -> id).toMap

  /** The vertex-field assets and the surface they are defined on, from the manifest. */
  private val fields: List[(String, String)] =
    manifest.hcursor.downField("resultFields").values.get.toList.flatMap(
      _.hcursor.downField("representations").values.get.toList.flatMap { r =>
        val c = r.hcursor
        for
          kind <- c.get[String]("kind").toOption if kind == "surface"
          asset <- c.get[String]("asset").toOption
          surface <- c.get[String]("surface").toOption
        yield (asset, surface)
      }
    ).distinct

  private def payloadOf(domainId: String): SurfaceVertices.Payload =
    val d = domains.find(_.hcursor.get[String]("id").toOption.contains(domainId)).get
    SurfaceVertices.readPayload("", d.hcursor.downField("descriptor").downField("payload").focus.get)
      .fold(ps => fail(ps.mkString("; ")), identity)

  private def keyOf(domainId: String): String =
    domains.find(_.hcursor.get[String]("id").toOption.contains(domainId)).get
      .hcursor.downField("key").get[String]("structuralFingerprint").toOption.get

  private def write(jsonRel: String, binRel: String, hdr: String, payload: Array[Byte]): Unit =
    if sys.env.contains("NP_WRITE_FIXTURES") || !FixtureIO.exists(binRel) then
      FixtureIO.writeBytes(jsonRel, hdr.getBytes("UTF-8"))
      FixtureIO.writeBytes(binRel, payload)
    assertEquals(FixtureIO.readText(jsonRel), hdr, s"$jsonRel drifted")
    assert(java.util.Arrays.equals(FixtureIO.readBytes(binRel), payload), s"$binRel drifted")

  test("GIFTI surfaces and fields encode deterministically to the committed renditions") {
    surfaces.foreach { (asset, hemisphere, _, domainId) =>
      val g = GiftiSurfaceReader.read(
        FixtureIO.root.resolve(s"reference/assets/$asset.surf.gii"),
        Hemisphere.fromString(hemisphere),
        SurfaceKind.Pial
      )
      assertEquals((g.vertexCount, g.faceCount), (642, 1280))
      // the fixture's own transform: the identity into scanner space, so world positions are the
      // GIFTI's positions and the rendition records the transform as provenance
      assertEquals(g.surfaceToWorld.data.toVector, SurfaceRendition.Identity.flatten)
      val r = SurfaceRendition.encode(
        g,
        payloadOf(domainId).space,
        Some(s"assets/$asset.surf.gii"),
        Some(SourceTransform(
          SurfaceRendition.Identity,
          Some("NIFTI_XFORM_SCANNER_ANAT"),
          Some("NIFTI_XFORM_SCANNER_ANAT")
        )),
        Some(if hemisphere == "left" then "CortexLeft" else "CortexRight")
      ).fold(fail(_), identity)
      write(
        s"reference/renditions/$asset.json",
        s"reference/renditions/$asset.bin",
        SurfaceRendition.headerJson(r.header),
        r.payload
      )
    }
    fields.foreach { (asset, surface) =>
      val doc = GiftiReader.read(FixtureIO.root.resolve(s"reference/assets/$asset.func.gii"))
        .fold(e => fail(e.message), identity)
      val values = GiftiReader.doubleData(doc.dataArrays.head).fold(e => fail(e.message), identity)
      assertEquals(values.length, 642)
      val r = VertexFieldRendition.encode(surface, values, Some(s"assets/$asset.func.gii"))
      write(
        s"reference/renditions/$asset.json",
        s"reference/renditions/$asset.f32",
        VertexFieldRendition.headerJson(r.header),
        r.payload
      )
    }
  }

  test("each surface's triangles hash to the surface-vertices key in the reference manifest") {
    surfaces.foreach { (asset, hemisphere, id, domainId) =>
      val g = GiftiSurfaceReader.read(
        FixtureIO.root.resolve(s"reference/assets/$asset.surf.gii"),
        Hemisphere.fromString(hemisphere),
        SurfaceKind.Pial
      )
      val p = payloadOf(domainId)
      assertEquals((p.vertexCount, p.faceCount, p.hemisphere), (642, 1280, hemisphere))
      // `topology` names the asset whose bytes are hashed, never the surfaces[] entry's id
      assertEquals(p.topology, asset)
      assertNotEquals(p.topology, id)
      assertEquals(surfaceIdOf(asset), id)
      val schema = TrustedSchemas.SurfaceVerticesV1
      assertEquals(
        SurfaceVertices.fingerprint(schema.id, schema.version, p, g.mesh.faceIndices).render,
        keyOf(domainId),
        asset
      )
    }
  }
