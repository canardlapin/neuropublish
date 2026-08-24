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

  private def resultField(
      display: Option[Json],
      measure: String,
      label: Option[String]
  ) =
    ResultField(
      "speech-t",
      label,
      "est",
      measure,
      "dom",
      Map.empty,
      List(Representation("surface", "t-lh", Some("lh-pial"), Some("left"), None)),
      None,
      display
    )

  private def loaded(
      display: Option[Json],
      measure: String = "org.neuropublish.measure/t-statistic",
      label: Option[String] = None,
      raw: Json = Json.obj()
  ) =
    val m = Manifest(
      core = "org.neuropublish.core/manifest@1",
      title = "Display recommendation",
      synopsis = None,
      sensitivity = None,
      assets = Nil,
      resultFields = List(resultField(display, measure, label)),
      underlays = List(Underlay("underlay", "mni", "Underlay")),
      surfaces = List(Surface("lh-pial", "lh-pial", "dom-lh", "left", "pial", "lh-pial")),
      analyses = Nil,
      domains = Nil,
      warnings = Nil,
      migratedFrom = None,
      raw = raw
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
    val recommendation = Json.obj(
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
    )
    val L = loaded(Some(recommendation))
    val f = L.field("speech-t").getOrElse(fail("field missing"))
    val d = L.published(f)
    assertEquals(d.window, Window(-2.0, 8.0))
    assertEquals(d.threshold, Threshold("positive", 2.3))
    assertEquals(d.colormap, "viridis-2")
    assertEquals(d.opacity, 0.5)
    assertEquals(L.preferenceApplication(f), PreferenceApplication.Applied)
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
    assertEquals(d.visible, true)
    assertEquals(d.colormap, "cold-hot")
    assertEquals(L.initialWorkspace.layers.head.recommended, false)
    assertEquals(
      L.preferenceApplication(L.field("speech-t").getOrElse(fail("field missing"))),
      PreferenceApplication.NotProvided
    )
  }

  test("the producer field label is preferred while the raw semantic id remains available") {
    val semanticId = "org.fmrigds.measure/between-study-heterogeneity"
    val L = loaded(None, semanticId, Some("Between-study heterogeneity (τ²)"))
    val f = L.field("speech-t").getOrElse(fail("field missing"))
    assertEquals(L.labelOf(f), "Between-study heterogeneity (τ²)")
    assertEquals(f.measure, semanticId)
  }

  test("unknown semantics stay generic and an unsupported palette has an explicit fallback") {
    val semanticId = "org.fmrigds.measure/between-study-heterogeneity"
    val recommendation = Json.obj(
      "threshold" -> Json.obj(
        "mode" -> Json.fromString("off"),
        "min" -> Json.fromDoubleOrNull(0.0)
      ),
      "window" -> Json.obj(
        "min" -> Json.fromDoubleOrNull(0.0),
        "max" -> Json.fromDoubleOrNull(1.0)
      ),
      "colormap" -> Json.fromString("fmrigds-heterogeneity"),
      "opacity" -> Json.fromDoubleOrNull(0.7)
    )
    val L = loaded(
      Some(recommendation),
      semanticId,
      Some("Between-study heterogeneity (τ²)"),
      Json.obj(
        "resultFields" -> Json.arr(Json.obj(
          "shortLabel" -> Json.fromString("τ²"),
          "inferential" -> Json.True,
          "signed" -> Json.True
        ))
      )
    )
    val f = L.field("speech-t").getOrElse(fail("field missing"))
    val d = L.published(f)
    assertEquals(d.visible, false)
    assertEquals(d.threshold, Threshold("off", 0.0))
    assertEquals(d.window, Window(0.0, 1.0))
    assertEquals(d.opacity, 0.7)
    assertEquals(d.colormap, "viridis-2") // neutral sequential default, not cold–hot
    val rawField = L.manifest.raw.hcursor.downField("resultFields").downArray
    assertEquals(rawField.get[Boolean]("inferential"), Right(true))
    assertEquals(rawField.get[Boolean]("signed"), Right(true))
    assertEquals(
      L.preferenceApplication(f),
      PreferenceApplication.UnsupportedWithFallback(
        "colormap",
        "fmrigds-heterogeneity",
        "viridis-2"
      )
    )
    val layer = L.initialWorkspace.layers.head
    assertEquals(layer.representations.surface, true) // still a renderable generic scalar map
    assertEquals(layer.current, d)
    assert(Colormaps.ramp(d.colormap) != null)
    intercept[IllegalArgumentException](Colormaps.ramp("fmrigds-heterogeneity"))
  }

  test("a familiar suffix grants no behavior without a trusted measure lookup") {
    val L = loaded(None, "org.hostile.measure/t-statistic")
    val f = L.field("speech-t").getOrElse(fail("field missing"))
    val d = L.published(f)
    assertEquals(d.visible, false)
    assertEquals(d.threshold, Threshold("off", 0.0))
    assertEquals(d.colormap, "viridis-2")
  }
