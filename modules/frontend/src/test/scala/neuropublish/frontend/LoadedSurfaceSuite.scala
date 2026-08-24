package neuropublish.frontend

import io.circe.Json
import munit.FunSuite
import neuropublish.api.RevisionDetail
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.*
import scalafim.image.DMat
import scalafim.surface.{Hemisphere, SurfaceField, SurfaceGeometry, SurfaceKind, TriangleMesh}

/** The revision facts the surface pane is built from, for a revision that declares two surfaces on
  * one hemisphere (`lh-pial` and `lh-white`) — the shape that used to collide two layers onto one
  * id, blank the pane, and still claim "drawn in: left surface".
  */
class LoadedSurfaceSuite extends FunSuite:
  private def geometry(h: Hemisphere, kind: SurfaceKind) =
    SurfaceGeometry(
      TriangleMesh.fromRows(
        Vector(
          Vector(0.0, 0.0, 0.0),
          Vector(10.0, 0.0, 0.0),
          Vector(0.0, 10.0, 0.0),
          Vector(0.0, 0.0, 10.0)
        ),
        Vector((0, 1, 2), (0, 1, 3), (0, 2, 3), (1, 2, 3))
      ),
      h,
      kind,
      DMat.fromRows(Vector(
        Vector(1.0, 0.0, 0.0, 0.0),
        Vector(0.0, 1.0, 0.0, 0.0),
        Vector(0.0, 0.0, 1.0, 0.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      ))
    )

  private def field(g: SurfaceGeometry) =
    SurfaceField(g, Array(0, 1, 2, 3), Array(1.0, 2.0, 3.0, 4.0))

  private def surfaceDecl(id: String, hemisphere: String, kind: String) =
    Surface(id, id, s"dom-$id", hemisphere, kind, id)

  private def rep(
      asset: String,
      surface: String,
      hemisphere: String,
      derivation: Option[String] = None
  ) =
    Representation("surface", asset, Some(surface), Some(hemisphere), derivation)

  private def resultField(id: String, reps: List[Representation]) =
    ResultField(
      id,
      None,
      "est",
      "org.neuropublish.measure/t-statistic",
      "dom",
      Map.empty,
      reps,
      None,
      None
    )

  private def manifest(surfaces: List[Surface], fields: List[ResultField], raw: Json = Json.obj()) =
    Manifest(
      core = "org.neuropublish.core/manifest@1",
      title = "Two surfaces on one hemisphere",
      synopsis = None,
      sensitivity = None,
      assets = Nil,
      resultFields = fields,
      underlays = List(Underlay("underlay", "mni", "Underlay")),
      surfaces = surfaces,
      analyses = Nil,
      domains = Nil,
      warnings = Nil,
      migratedFrom = None,
      raw = raw
    )

  private def loaded(
      m: Manifest,
      decoded: List[(String, SurfaceGeometry)],
      fieldAssets: List[String]
  ) =
    Loaded(
      "ws",
      "p",
      RevisionDetail("rev", "ws", "p", None, "sha256:0", None, "now", Json.obj(), Nil),
      m,
      volumes = Map.empty,
      summaries = Map.empty,
      surfaces = decoded.map((id, g) =>
        id -> (Loaded.surfaceDecls(m).find(_.id == id).getOrElse(fail(s"no decl $id")), g)
      ).toMap,
      vertexFields = fieldAssets.map(a =>
        a -> field(decoded.head._2)
      ).toMap
    )

  private val lhPial = geometry(Hemisphere.Left, SurfaceKind.Pial)
  private val lhWhite = geometry(Hemisphere.Left, SurfaceKind.White)
  private val rhPial = geometry(Hemisphere.Right, SurfaceKind.Pial)

  test("two left surfaces: only the targeted one is placed and only it is drawn") {
    val m = manifest(
      List(
        surfaceDecl("lh-pial", "left", "pial"),
        surfaceDecl("lh-white", "left", "white"),
        surfaceDecl("rh-pial", "right", "pial")
      ),
      List(resultField(
        "speech-t",
        List(rep("t-lh-white", "lh-white", "left"), rep("t-rh", "rh-pial", "right"))
      ))
    )
    val L = loaded(
      m,
      List(("lh-pial", lhPial), ("lh-white", lhWhite), ("rh-pial", rhPial)),
      List("t-lh-white", "t-rh")
    )
    assertEquals(
      L.placedSurfaces.view.mapValues(_.id).toMap,
      Map("left" -> "lh-white", "right" -> "rh-pial")
    )
    val f = L.field("speech-t").getOrElse(fail("field missing"))
    assertEquals(L.surfaceRepsOf(f).map(_.surface), List("lh-white", "rh-pial"))
    assertEquals(L.undrawnSurfaceRepsOf(f), Nil)
    assertEquals(L.representationsOf(f).surfaces, Set("left", "right"))
    // one layer per placed surface: the ids cannot collide, which is what blanked the pane
    val ids = L.surfaceRepsOf(f).map(r => SurfacePlacement.layerId(f.id, r.surface))
    assertEquals(ids.distinct.length, ids.length)
    assertEquals(ids, List("speech-t@lh-white", "speech-t@rh-pial"))
  }

  test("a field on both left surfaces is drawn once, and says which surface it is not drawn on") {
    val m = manifest(
      List(surfaceDecl("lh-pial", "left", "pial"), surfaceDecl("lh-white", "left", "white")),
      List(resultField(
        "speech-t",
        List(rep("t-pial", "lh-pial", "left"), rep("t-white", "lh-white", "left"))
      ))
    )
    val L = loaded(m, List(("lh-pial", lhPial), ("lh-white", lhWhite)), List("t-pial", "t-white"))
    val f = L.field("speech-t").getOrElse(fail("field missing"))
    assertEquals(L.placedSurfaces("left").id, "lh-pial") // manifest order breaks the tie
    assertEquals(L.surfaceRepsOf(f).map(_.surface), List("lh-pial"))
    assertEquals(L.undrawnSurfaceRepsOf(f).map(_.surface), List("lh-white"))
    assertEquals(L.representationsOf(f).surfaces, Set("left"))
  }

  test("a field only on the unplaced surface is never claimed as drawn; the surface is named") {
    val m = manifest(
      List(surfaceDecl("lh-pial", "left", "pial"), surfaceDecl("lh-white", "left", "white")),
      List(
        resultField("speech-t", List(rep("t-pial", "lh-pial", "left"))),
        resultField("speech-z", List(rep("z-white", "lh-white", "left")))
      )
    )
    val L = loaded(m, List(("lh-pial", lhPial), ("lh-white", lhWhite)), List("t-pial", "z-white"))
    assertEquals(L.placedSurfaces("left").id, "lh-pial")
    val z = m.resultFields.find(_.id == "speech-z").getOrElse(fail("field missing"))
    assertEquals(L.surfaceRepsOf(z), Nil)
    assertEquals(L.representationsOf(z).surfaces, Set.empty[String])
    assertEquals(L.undrawnSurfaceRepsOf(z).map(_.surface), List("lh-white"))
    // it is not offered as a layer at all — it has nothing to draw — and the pane names why
    assertEquals(L.fields.map(_.id), List("speech-t"))
    assertEquals(L.unplacedSurfaces.map(_.id), List("lh-white"))
  }

  test("a surface representation on an undecoded surface is neither drawn nor counted") {
    val m = manifest(
      List(surfaceDecl("lh-pial", "left", "pial")),
      List(resultField("speech-t", List(rep("t-pial", "lh-pial", "left"))))
    )
    val L = loaded(m, Nil, Nil).copy(surfaces = Map.empty, vertexFields = Map.empty)
    assertEquals(L.placedSurfaces, Map.empty[String, SurfaceDecl])
    assertEquals(L.fields, Nil) // no volume either: nothing to draw
  }

  test("derivation is read from the provenance activities, and its absence is not invented") {
    val raw = Json.obj(
      "provenance" -> Json.obj(
        "activities" -> Json.arr(
          Json.obj(
            "id" -> Json.fromString("project-to-surface"),
            "schema" -> Json.obj(
              "id" -> Json.fromString("org.example.julia/surface-projection"),
              "version" -> Json.fromString("0.1")
            ),
            "payload" -> Json.obj("method" -> Json.fromString("synthetic"))
          )
        )
      )
    )
    val m = manifest(
      List(surfaceDecl("lh-pial", "left", "pial")),
      List(
        resultField(
          "speech-t",
          List(rep("t-pial", "lh-pial", "left", Some("project-to-surface")))
        ),
        resultField("speech-z", List(rep("z-pial", "lh-pial", "left")))
      ),
      raw
    )
    val L = loaded(m, List(("lh-pial", lhPial)), List("t-pial", "z-pial"))
    val t = L.field("speech-t").getOrElse(fail("field missing"))
    assertEquals(
      L.derivationOf(t, "lh-pial"),
      Some((
        "project-to-surface",
        "org.example.julia/surface-projection @ 0.1",
        Some("method: synthetic")
      ))
    )
    assertEquals(L.derivationOf(L.field("speech-z").get, "lh-pial"), None)
  }

  test("unused asset digests stay out of the way of the placement rule") {
    // guards the helper: an asset list is irrelevant to placement
    val m = manifest(List(surfaceDecl("lh-pial", "left", "pial")), Nil)
      .copy(assets =
        List(ManifestAsset("lh-pial", Sha256.unsafe("sha256:" + "0" * 64), 1L, "x", None))
      )
    val L = loaded(m, List(("lh-pial", lhPial)), Nil)
    assertEquals(L.placedSurfaces("left").id, "lh-pial")
  }
