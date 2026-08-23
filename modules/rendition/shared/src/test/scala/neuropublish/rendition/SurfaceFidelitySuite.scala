package neuropublish.rendition

import io.circe.Json
import munit.FunSuite
import scalafim.surface.*

/** Stage 5 surface rendition fidelity (plan, "surface fidelity"; carried from Stage 0): the decode
  * of the committed surface-mesh and vertex-field renditions — on the JVM and on Scala.js alike —
  * reproduces the Julia producer's oracle exactly: vertex coordinates, faces, counts, field values,
  * sums; the decoded topology hashes to the header's identity and to its `faceDigest`; and a decode
  * is the identity on re-encode.
  *
  * The oracle is written by the same Julia program that writes the assets, so on its own it proves
  * round-tripping rather than correctness (review S9). The independent evidence is elsewhere and is
  * kept here alongside: the fingerprint agreement across Scala, Julia, and R (`FixtureSuite`,
  * `GiftiToRenditionSuite`), and the hand-derived counts and vertex position below, which come from
  * the icosphere construction rather than from any file.
  */
class SurfaceFidelitySuite extends FunSuite:
  /** Surface geometry assets; the rendition files are named by asset id. */
  private val surfaceAssets = List("lh-pial", "rh-pial")
  private val fieldIds = List("speech-t-lh", "speech-t-rh", "speech-z-lh", "speech-z-rh")

  private lazy val oracle: Json =
    _root_.io.circe.parser.parse(FixtureIO.readText("reference/assets/oracle.json")).fold(
      throw _,
      identity
    )
  private def entries(section: String): Map[String, Json] =
    oracle.hcursor.downField(section).values.get.toList
      .map(j => j.hcursor.get[String]("id").toOption.get -> j).toMap

  /** A `surfaces[].id` is not its `asset`: the manifest is what resolves one to the other, and the
    * oracle (the producer's own naming) speaks in asset ids.
    */
  private lazy val surfaceIdOf: Map[String, String] =
    _root_.io.circe.parser.parse(FixtureIO.readText("reference/manifest.json")).toOption.get
      .hcursor.downField("surfaces").values.get.toList.map { s =>
        s.hcursor.get[String]("asset").toOption.get -> s.hcursor.get[String]("id").toOption.get
      }.toMap
  private lazy val assetOf: Map[String, String] = surfaceIdOf.map(_.swap)

  private lazy val headers: Map[String, SurfaceRenditionHeader] = surfaceAssets.map { id =>
    id -> SurfaceRendition.decodeHeader(FixtureIO.readText(s"reference/renditions/$id.json"))
      .fold(m => fail(s"$id: $m"), identity)
  }.toMap
  private lazy val surfaces: Map[String, SurfaceGeometry] = surfaceAssets.map { id =>
    id -> SurfaceRendition.decode(headers(id), FixtureIO.readBytes(s"reference/renditions/$id.bin"))
      .fold(m => fail(s"$id: $m"), identity)
  }.toMap

  test("surface headers carry the counts, hemisphere, kind, space, RAS+ identity transform") {
    entries("surfaces").foreach { (id, o) =>
      val h = headers(id)
      assertEquals(h.vertexCount, o.hcursor.get[Int]("vertexCount").toOption.get, id)
      assertEquals(h.faceCount, o.hcursor.get[Int]("faceCount").toOption.get, id)
      assertEquals(h.hemisphere, o.hcursor.get[String]("hemisphere").toOption.get, id)
      assertEquals(h.kind, "pial")
      assertEquals(h.coordinateSystem, "RAS+")
      // the payload is world positions: the GIFTI's transform was applied at ingestion and is
      // kept as provenance, so surfaceToWorld is the identity and nothing applies it twice
      assertEquals(h.surfaceToWorld, SurfaceRendition.Identity)
      assertEquals(h.sourceTransform.flatMap(_.transformedSpace), Some("NIFTI_XFORM_SCANNER_ANAT"))
      assertEquals(h.sourceTransform.map(_.matrix), Some(SurfaceRendition.Identity))
      assertEquals(
        h.anatomicalStructurePrimary,
        Some(if id.startsWith("lh") then "CortexLeft" else "CortexRight"),
        id
      )
      // the space a reader compares against a volume's before it links the two
      assertEquals(h.space, "MNI152NLin2009cAsym", id)
      assert(h.faceDigest.exists(_.startsWith("sha256:")), h.faceDigest)
      assertEquals(h.source.isDefined, true)
    }
  }

  test("counts and one vertex follow from the icosphere construction, not from the oracle") {
    // an icosahedron subdivided n times has V = 10·4^n + 2 vertices and F = 20·4^n faces; the
    // producer subdivides three times (S9: derived here, never read back from a fixture)
    val n = 3
    val vertices = 10 * math.pow(4.0, n.toDouble).toInt + 2
    val faces = 20 * math.pow(4.0, n.toDouble).toInt
    surfaceAssets.foreach { id =>
      assertEquals((headers(id).vertexCount, headers(id).faceCount), (vertices, faces), id)
      assertEquals((surfaces(id).vertexCount, surfaces(id).faceCount), (vertices, faces), id)
    }
    // vertex 0 is the icosahedron's first vertex, (-1, φ, 0) normalized, scaled to the producer's
    // 25 mm radius and offset ∓30 mm in x; subdivision appends midpoints and never moves it.
    // Positions are written as float32, hence the tolerance.
    val phi = (1.0 + math.sqrt(5.0)) / 2.0
    val norm = math.sqrt(1.0 + phi * phi)
    val radius = 25.0
    val offset = 30.0
    List(("lh-pial", -1.0), ("rh-pial", 1.0)).foreach { (id, sign) =>
      val p = surfaces(id).mesh.vertex(VertexId(0))
      val expected =
        Vector(radius * (-1.0 / norm) + sign * offset, radius * (phi / norm), 0.0)
      Vector(p.x, p.y, p.z).zip(expected).foreach((got, want) =>
        assertEqualsDouble(got, want, 1e-4, s"$id vertex 0")
      )
    }
  }

  test("decoded vertices and faces equal the oracle exactly; the topology hashes to the header") {
    entries("surfaces").foreach { (id, o) =>
      val g = surfaces(id)
      val probes = o.hcursor.get[Vector[Int]]("probeVertices").toOption.get
      val coords = o.hcursor.get[Vector[Vector[Double]]]("coordinates").toOption.get
      probes.zip(coords).foreach { (v, xyz) =>
        val p = g.mesh.vertex(VertexId(v))
        assertEquals(Vector(p.x, p.y, p.z), xyz, s"$id vertex $v")
      }
      val face0 = g.mesh.face(FaceId(0))
      assertEquals(
        Vector(face0.a.index, face0.b.index, face0.c.index),
        o.hcursor.get[Vector[Int]]("face0").toOption.get,
        s"$id face 0"
      )
      val last = g.mesh.face(FaceId(g.faceCount - 1))
      assertEquals(
        Vector(last.a.index, last.b.index, last.c.index),
        o.hcursor.get[Vector[Int]]("faceLast").toOption.get,
        s"$id last face"
      )
      assertEquals(g.mesh.topologyIdentity.stableKey, headers(id).topologyIdentity, id)
      assertEquals(
        Some(SurfaceRendition.faceDigest(g.mesh.faceIndices)),
        headers(id).faceDigest,
        id
      )
      assertEquals(g.hemisphere.code, if id.startsWith("lh") then "lh" else "rh")
      assertEquals(g.meshDomainEither.map(_.vertexCount), Right(g.vertexCount))
    }
  }

  test("both hemispheres share one ordered topology; their coordinates differ") {
    val l = surfaces("lh-pial"); val r = surfaces("rh-pial")
    assert(l.mesh.hasSameTopology(r.mesh))
    assertEquals(l.mesh.topologyIdentity.stableKey, r.mesh.topologyIdentity.stableKey)
    assertEquals(headers("lh-pial").faceDigest, headers("rh-pial").faceDigest)
    assert(!l.hasSameMeshDomain(r), "different hemispheres are different mesh domains")
    assertNotEquals(l.mesh.vertex(VertexId(0)).x, r.mesh.vertex(VertexId(0)).x)
  }

  test("vertex fields decode onto their surface with the oracle's values and sums") {
    entries("fields").foreach { (id, o) =>
      val h =
        VertexFieldRendition.decodeHeader(FixtureIO.readText(s"reference/renditions/$id.json"))
          .fold(m => fail(s"$id: $m"), identity)
      // the header names the `surfaces[].id`; the oracle names the asset the producer wrote
      assertEquals(h.surface, surfaceIdOf(o.hcursor.get[String]("surface").toOption.get), id)
      assertEquals(h.vertexCount, o.hcursor.get[Int]("vertexCount").toOption.get, id)
      val field = VertexFieldRendition.decode(
        h,
        FixtureIO.readBytes(s"reference/renditions/$id.f32"),
        surfaces(assetOf(h.surface))
      ).fold(m => fail(s"$id: $m"), identity)
      assertEquals(field.size, h.vertexCount)
      val probes = o.hcursor.get[Vector[Int]]("probeVertices").toOption.get
      val values = o.hcursor.get[Vector[Double]]("values").toOption.get
      probes.zip(values).foreach { (v, x) =>
        assertEquals(field.valueAt(VertexId(v)), Some(x), s"$id at vertex $v")
      }
      var sum = 0.0; var i = 0
      while i < field.data.length do { sum += field.data(i); i += 1 }
      assertEquals(sum, o.hcursor.get[Double]("sum").toOption.get, s"$id sum")
      val sm = h.summary.getOrElse(fail(s"$id has no summary"))
      assertEquals(sm.finite, h.vertexCount)
      assertEquals(sm.missing, 0)
      assert(sm.min <= sm.max)
    }
  }

  test("a field refuses a surface with another vertex count; a mesh refuses a foreign topology") {
    val h = VertexFieldRendition.decodeHeader(
      FixtureIO.readText("reference/renditions/speech-t-lh.json")
    ).toOption.get
    val tetra = SurfaceGeometry(
      TriangleMesh.fromRows(
        Vector(
          Vector(0.0, 0.0, 0.0),
          Vector(1.0, 0.0, 0.0),
          Vector(0.0, 1.0, 0.0),
          Vector(0.0, 0.0, 1.0)
        ),
        Vector((0, 1, 2), (0, 1, 3))
      ),
      Hemisphere.Left
    )
    val bytes = FixtureIO.readBytes("reference/renditions/speech-t-lh.f32")
    assert(VertexFieldRendition.decode(h, bytes, tetra).left.exists(_.contains("vertices")))
    assert(VertexFieldRendition.decode(h, bytes.take(8), surfaces("lh-pial")).isLeft)
    val mesh = headers("lh-pial")
    val payload = FixtureIO.readBytes("reference/renditions/lh-pial.bin")
    // swap the first two vertex ordinals of face 0: same vertex set, different ordered topology
    val swapped = payload.clone()
    val base = mesh.vertexCount * 12
    for k <- 0 until 4 do
      val a = swapped(base + k); swapped(base + k) = swapped(base + 4 + k);
      swapped(base + 4 + k) = a
    // the SHA-256 face digest catches it first; without one the reference implementation's
    // topology key still does (S11: the digest is the stable identity, the key is ScalaFIM's)
    assert(SurfaceRendition.decode(mesh, swapped).left.exists(_.contains("face digest")))
    assert(
      SurfaceRendition.decode(mesh.copy(faceDigest = None), swapped).left
        .exists(_.contains("topology identity"))
    )
    assert(SurfaceRendition.decode(mesh, payload.take(100)).left.exists(_.contains("bytes")))
  }

  test("decode then encode is the identity on header and payload") {
    surfaceAssets.foreach { id =>
      val h = headers(id)
      val r = SurfaceRendition.encode(
        surfaces(id),
        h.space,
        h.source,
        h.sourceTransform,
        h.anatomicalStructurePrimary
      ).fold(fail(_), identity)
      assertEquals(r.header, h, id)
      assert(
        java.util.Arrays.equals(r.payload, FixtureIO.readBytes(s"reference/renditions/$id.bin")),
        s"$id payload"
      )
    }
    fieldIds.foreach { id =>
      val h =
        VertexFieldRendition.decodeHeader(FixtureIO.readText(s"reference/renditions/$id.json"))
          .toOption.get
      val bytes = FixtureIO.readBytes(s"reference/renditions/$id.f32")
      val field = VertexFieldRendition.decode(h, bytes, surfaces(assetOf(h.surface))).toOption.get
      val r = VertexFieldRendition.encode(field, h.surface, h.source)
      assertEquals(r.header, h, id)
      assert(java.util.Arrays.equals(r.payload, bytes), s"$id payload")
    }
  }

  test("a rendition of positions that are not world positions is refused") {
    val g = surfaces("lh-pial")
    val shifted = SurfaceGeometry(
      g.mesh,
      g.hemisphere,
      g.kind,
      scalafim.image.DMat.fromRows(Vector.tabulate(4, 4)((r, c) =>
        if r == c then 1.0 else if c == 3 then 5.0 else 0.0
      ))
    )
    assert(
      SurfaceRendition.encode(shifted, "MNI152NLin2009cAsym").left.exists(_.contains("world")),
      "a non-identity surfaceToWorld must not be encoded as if the payload were world positions"
    )
    assert(SurfaceRendition.encode(g, "  ").isLeft, "the space is required")
  }

  test("header validation names the fault") {
    val good = FixtureIO.readText("reference/renditions/lh-pial.json")
    assert(SurfaceRendition.decodeHeader(good.replace("surface-mesh@0", "surface-mesh@9")).isLeft)
    assert(SurfaceRendition.decodeHeader(good.replace("\"RAS+\"", "\"LPS+\"")).isLeft)
    assert(SurfaceRendition.decodeHeader(good.replace("\"left\"", "\"both\"")).isLeft)
    assert(SurfaceRendition.decodeHeader(good.replace("sha256:", "sha255:")).isLeft)
    val f = FixtureIO.readText("reference/renditions/speech-t-lh.json")
    assert(VertexFieldRendition.decodeHeader(f.replace(
      "vertex-field-f32@0",
      "volume-f32@0"
    )).isLeft)
  }
