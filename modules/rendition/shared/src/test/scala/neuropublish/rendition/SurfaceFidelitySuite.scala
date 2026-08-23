package neuropublish.rendition

import io.circe.Json
import munit.FunSuite
import scalafim.surface.*

/** Stage 5 surface rendition fidelity (plan, "surface fidelity"; carried from Stage 0): the decode
  * of the committed surface-mesh and vertex-field renditions — on the JVM and on Scala.js alike —
  * reproduces the Julia producer's oracle exactly: vertex coordinates, faces, counts, field values,
  * sums; the decoded topology hashes to the header's identity; and a decode is the identity on
  * re-encode.
  */
class SurfaceFidelitySuite extends FunSuite:
  private val surfaceIds = List("lh-pial", "rh-pial")
  private val fieldIds = List("speech-t-lh", "speech-t-rh", "speech-z-lh", "speech-z-rh")

  private lazy val oracle: Json =
    _root_.io.circe.parser.parse(FixtureIO.readText("reference/assets/oracle.json")).fold(
      throw _,
      identity
    )
  private def entries(section: String): Map[String, Json] =
    oracle.hcursor.downField(section).values.get.toList
      .map(j => j.hcursor.get[String]("id").toOption.get -> j).toMap

  private lazy val headers: Map[String, SurfaceRenditionHeader] = surfaceIds.map { id =>
    id -> SurfaceRendition.decodeHeader(FixtureIO.readText(s"reference/renditions/$id.json"))
      .fold(m => fail(s"$id: $m"), identity)
  }.toMap
  private lazy val surfaces: Map[String, SurfaceGeometry] = surfaceIds.map { id =>
    id -> SurfaceRendition.decode(headers(id), FixtureIO.readBytes(s"reference/renditions/$id.bin"))
      .fold(m => fail(s"$id: $m"), identity)
  }.toMap

  test("surface headers carry the counts, hemisphere, kind, RAS+ identity transform") {
    entries("surfaces").foreach { (id, o) =>
      val h = headers(id)
      assertEquals(h.vertexCount, o.hcursor.get[Int]("vertexCount").toOption.get, id)
      assertEquals(h.faceCount, o.hcursor.get[Int]("faceCount").toOption.get, id)
      assertEquals(h.hemisphere, o.hcursor.get[String]("hemisphere").toOption.get, id)
      assertEquals(h.kind, "pial")
      assertEquals(h.coordinateSystem, "RAS+")
      assertEquals(h.surfaceToWorld, Vector.tabulate(4, 4)((r, c) => if r == c then 1.0 else 0.0))
      assertEquals(h.source.isDefined, true)
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
      assertEquals(g.hemisphere.code, if id.startsWith("lh") then "lh" else "rh")
      assertEquals(g.meshDomainEither.map(_.vertexCount), Right(g.vertexCount))
    }
  }

  test("both hemispheres share one ordered topology; their coordinates differ") {
    val l = surfaces("lh-pial"); val r = surfaces("rh-pial")
    assert(l.mesh.hasSameTopology(r.mesh))
    assertEquals(l.mesh.topologyIdentity.stableKey, r.mesh.topologyIdentity.stableKey)
    assert(!l.hasSameMeshDomain(r), "different hemispheres are different mesh domains")
    assertNotEquals(l.mesh.vertex(VertexId(0)).x, r.mesh.vertex(VertexId(0)).x)
  }

  test("vertex fields decode onto their surface with the oracle's values and sums") {
    entries("fields").foreach { (id, o) =>
      val h =
        VertexFieldRendition.decodeHeader(FixtureIO.readText(s"reference/renditions/$id.json"))
          .fold(m => fail(s"$id: $m"), identity)
      assertEquals(h.surface, o.hcursor.get[String]("surface").toOption.get, id)
      assertEquals(h.vertexCount, o.hcursor.get[Int]("vertexCount").toOption.get, id)
      val field = VertexFieldRendition.decode(
        h,
        FixtureIO.readBytes(s"reference/renditions/$id.f32"),
        surfaces(h.surface)
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
    assert(SurfaceRendition.decode(mesh, swapped).left.exists(_.contains("topology identity")))
    assert(SurfaceRendition.decode(mesh, payload.take(100)).left.exists(_.contains("bytes")))
  }

  test("decode then encode is the identity on header and payload") {
    surfaceIds.foreach { id =>
      val r = SurfaceRendition.encode(surfaces(id), headers(id).source).fold(fail(_), identity)
      assertEquals(r.header, headers(id), id)
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
      val field = VertexFieldRendition.decode(h, bytes, surfaces(h.surface)).toOption.get
      val r = VertexFieldRendition.encode(field, h.surface, h.source)
      assertEquals(r.header, h, id)
      assert(java.util.Arrays.equals(r.payload, bytes), s"$id payload")
    }
  }

  test("header validation names the fault") {
    val good = FixtureIO.readText("reference/renditions/lh-pial.json")
    assert(SurfaceRendition.decodeHeader(good.replace("surface-mesh@0", "surface-mesh@9")).isLeft)
    assert(SurfaceRendition.decodeHeader(good.replace("\"RAS+\"", "\"LPS+\"")).isLeft)
    assert(SurfaceRendition.decodeHeader(good.replace("\"left\"", "\"both\"")).isLeft)
    val f = FixtureIO.readText("reference/renditions/speech-t-lh.json")
    assert(VertexFieldRendition.decodeHeader(f.replace(
      "vertex-field-f32@0",
      "volume-f32@0"
    )).isLeft)
  }
