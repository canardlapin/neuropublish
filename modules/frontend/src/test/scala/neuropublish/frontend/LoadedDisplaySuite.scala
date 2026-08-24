package neuropublish.frontend

import io.circe.Json
import munit.FunSuite
import neuropublish.api.RevisionDetail
import neuropublish.protocol.json.*
import neuropublish.rendition.ScalarSummary
import neuropublish.viewer.{Threshold, Window}
import scalafim.image.DMat
import scalafim.surface.{Hemisphere, SurfaceField, SurfaceGeometry, SurfaceKind, TriangleMesh}

/** `publishedDisplay` is the producer's recommendation, and the viewer must read all of it. A field
  * the manifest schema admits but the viewer drops is worse than a typo: a typo is rejected by the
  * closed structure (SPEC §8), while a dropped field shows the producer a recommendation they never
  * made. These cases are deliberately *not* degenerate — the values differ from the viewer's own
  * defaults, so dropping one fails here.
  */
class LoadedDisplaySuite extends FunSuite:
  private val geometry =
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
      Hemisphere.Left,
      SurfaceKind.Pial,
      DMat.fromRows(Vector(
        Vector(1.0, 0.0, 0.0, 0.0),
        Vector(0.0, 1.0, 0.0, 0.0),
        Vector(0.0, 0.0, 1.0, 0.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      ))
    )

  private val summary =
    ScalarSummary(-4.0, 6.0, Vector.fill(7)(0.0), Vector.fill(32)(0), 4, 0, 0)

  private def resultField(display: Option[Json]) =
    ResultField(
      "speech-t",
      "est",
      "org.neuropublish.measure/t-statistic",
      "dom",
      Map.empty,
      List(Representation("surface", "t-lh", Some("lh-pial"), Some("left"), None)),
      None,
      display
    )

  private def loaded(display: Option[Json]) =
    val m = Manifest(
      core = "org.neuropublish.core/manifest@1",
      title = "Display recommendation",
      synopsis = None,
      sensitivity = None,
      assets = Nil,
      resultFields = List(resultField(display)),
      underlays = List(Underlay("underlay", "mni", "Underlay")),
      surfaces = List(Surface("lh-pial", "lh-pial", "dom-lh", "left", "pial", "lh-pial")),
      analyses = Nil,
      domains = Nil,
      warnings = Nil,
      migratedFrom = None,
      raw = Json.obj()
    )
    Loaded(
      "ws",
      "p",
      RevisionDetail("rev", "ws", "p", None, "sha256:0", None, "now", Json.obj(), Nil),
      m,
      volumes = Map.empty,
      summaries = Map("t-lh" -> summary),
      surfaces = Map("lh-pial" -> (Loaded.surfaceDecls(m).head, geometry)),
      vertexFields = Map("t-lh" -> SurfaceField(geometry, Array(0, 1, 2, 3), Array(1.0, 2, 3, 4)))
    )

  private def displayOf(json: Option[Json]) =
    val L = loaded(json)
    L.published(L.field("speech-t").getOrElse(fail("field missing")))

  test("every recommended field reaches the layer: window, threshold, colormap, and opacity") {
    val d = displayOf(Some(Json.obj(
      "threshold" -> Json.obj(
        "mode" -> Json.fromString("positive"),
        "min" -> Json.fromDoubleOrNull(2.3)
      ),
      "window" -> Json.obj(
        "min" -> Json.fromDoubleOrNull(-2.0),
        "max" -> Json.fromDoubleOrNull(8.0)
      ),
      "colormap" -> Json.fromString("viridis-2"),
      "opacity" -> Json.fromDoubleOrNull(0.5)
    )))
    assertEquals(d.window, Window(-2.0, 8.0))
    assertEquals(d.threshold, Threshold("positive", 2.3))
    assertEquals(d.colormap, "viridis-2")
    assertEquals(d.opacity, 0.5)
  }

  test("an omitted opacity is the viewer's default; an out-of-range one is not honoured") {
    val base = Json.obj(
      "threshold" -> Json.obj(
        "mode" -> Json.fromString("two-sided"),
        "min" -> Json.fromDoubleOrNull(3.1)
      ),
      "window" -> Json.obj(
        "min" -> Json.fromDoubleOrNull(-8.0),
        "max" -> Json.fromDoubleOrNull(8.0)
      ),
      "colormap" -> Json.fromString("cold-hot")
    )
    assertEquals(displayOf(Some(base)).opacity, 0.85)
    assertEquals(
      displayOf(Some(base.deepMerge(Json.obj(
        "opacity" -> Json.fromDoubleOrNull(1.5)
      )))).opacity,
      0.85
    )
  }

  test("an unknown threshold mode is not guessed") {
    val d = displayOf(Some(Json.obj(
      "threshold" -> Json.obj(
        "mode" -> Json.fromString("cluster-corrected"),
        "min" -> Json.fromDoubleOrNull(3.1)
      ),
      "window" -> Json.obj(
        "min" -> Json.fromDoubleOrNull(-8.0),
        "max" -> Json.fromDoubleOrNull(8.0)
      ),
      "colormap" -> Json.fromString("cold-hot")
    )))
    assertEquals(d.threshold, Threshold("off", 3.1))
  }

  test("no recommendation: the window is the data range and the layer is not 'published'") {
    val L = loaded(None)
    val d = L.published(L.field("speech-t").getOrElse(fail("field missing")))
    assertEquals(d.window, Window(-4.0, 6.0))
    assertEquals(d.threshold, Threshold("off", 0.0))
    assertEquals(L.initialWorkspace.layers.head.recommended, false)
  }
