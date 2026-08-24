package neuropublish.viewer

import io.circe.Json
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

class WorkspaceStateSuite extends ScalaCheckSuite:
  private val ids = List("speech-t", "speech-z", "b:c;d&e=f%g h")
  private val pub = LayerDisplay(true, 0.85, Window(-8, 8), Threshold("two-sided", 3.1), "cold-hot")
  private val reps = List(
    LayerRepresentations(volume = true, surfaces = Set("left", "right")),
    LayerRepresentations(volume = true),
    LayerRepresentations(volume = false, surfaces = Set("right"))
  )
  private val base = Workspace(
    ids.zip(reps).map((id, r) => WorkspaceLayer(id, pub, pub, true, r)).toVector,
    None,
    WorkspaceLayout.default,
    "layers"
  )
  private def repsOf(id: String) = base.layers.find(_.id == id).get.representations

  private val genDisplay: Gen[LayerDisplay] =
    for
      v <- Gen.oneOf(true, false)
      o <- Gen.choose(0.0, 1.0)
      lo <- Gen.choose(-20.0, 0.0)
      hi <- Gen.choose(0.5, 20.0)
      m <- Gen.oneOf(Threshold.Modes.toList)
      t <- Gen.choose(0.0, 10.0)
      // a maximum magnitude only exists for `two-sided`, so the generator only offers one there
      hiMag <- Gen.option(Gen.choose(11.0, 40.0))
      c <- Gen.oneOf(Colormap.Supported.toSeq)
    yield LayerDisplay(
      v,
      o,
      Window(lo, hi),
      m match
        case "off" => Threshold("off", 0.0)
        case "two-sided" => Threshold(m, t, hiMag)
        case _ => Threshold(m, t)
      ,
      c
    )

  private val genWorkspace: Gen[Workspace] =
    for
      order <- Gen.pick(ids.length, ids).map(_.toVector)
      displays <- Gen.listOfN(ids.length, genDisplay)
      cursor <- Gen.option(
        Gen.zip(Gen.choose(-90.0, 90.0), Gen.choose(-90.0, 90.0), Gen.choose(-90.0, 90.0))
      )
      preset <- Gen.oneOf(LayoutPreset.values.toList)
      nav <- Gen.choose(0.1, 0.4)
      ins <- Gen.choose(0.1, 0.4)
      split <- Gen.choose(0.1, 0.9)
      tab <- Gen.oneOf("layers", "analysis", "provenance")
      vp <- Gen.oneOf(SurfaceCameraState.Viewpoints)
      pr <- Gen.oneOf(SurfaceCameraState.Projections)
    yield Workspace(
      order.zip(displays).map((id, d) => WorkspaceLayer(id, pub, d, true, repsOf(id))),
      cursor,
      WorkspaceLayout(preset, nav, ins, split),
      tab,
      SurfaceCameraState(vp, pr)
    )
  given Arbitrary[Workspace] = Arbitrary(genWorkspace)

  property("encode/decode is the identity") {
    forAll { (w: Workspace) =>
      assertEquals(WorkspaceState.decode(WorkspaceState.encode(w)), Right(w))
    }
  }

  property("order and presentation survive a saved-view round trip applied onto the revision") {
    forAll { (w: Workspace) =>
      val restored = WorkspaceState.decode(WorkspaceState.encode(w)).toOption.map(
        WorkspaceState.apply(_, base)
      )
      assertEquals(restored, Some(w))
    }
  }

  test("the envelope is org.neuropublish.view/workspace-state@1 with a nested layout record") {
    val j = WorkspaceState.encode(base).hcursor
    assertEquals(
      j.downField("schema").downField("id").as[String],
      Right("org.neuropublish.view/workspace-state")
    )
    assertEquals(j.downField("schema").downField("version").as[String], Right("1"))
    val layout = j.downField("payload").downField("layout")
    assertEquals(
      layout.downField("schema").downField("id").as[String],
      Right("org.neuropublish.view/workspace-layout")
    )
    assertEquals(layout.downField("payload").downField("preset").as[String], Right("volume"))
    assertEquals(j.downField("payload").downField("cursor").focus.map(_.isNull), Some(true))
  }

  test("a Stage 4 record (no representations, split, or camera) still decodes with defaults") {
    val j = WorkspaceState.encode(base).mapObject(_.mapValues(identity))
    val stripped = j.hcursor.downField("payload").withFocus(_.mapObject(_.remove("surfaceCamera")))
      .downField("layout").downField("payload").withFocus(_.mapObject(_.remove("splitFraction")))
      .up.up.downField("layers").withFocus(_.mapArray(
        _.map(_.mapObject(_.remove("representations")))
      ))
      .top.get
    val decoded = WorkspaceState.decode(stripped)
    assertEquals(decoded.map(_.surfaceCamera), Right(SurfaceCameraState.default))
    assertEquals(decoded.map(_.layout.splitFraction), Right(0.5))
    assert(decoded.toOption.get.layers.forall(_.representations == LayerRepresentations()))
    // applied onto the revision, representations come back from the revision, not the record
    val applied = decoded.map(WorkspaceState.apply(_, base))
    assertEquals(
      applied.map(_.layers.map(_.representations)),
      Right(base.layers.map(_.representations))
    )
  }

  test("a saved view cannot change representations or smuggle an invalid camera") {
    val saved = base.copy(
      layers = base.layers.map(_.copy(representations = LayerRepresentations(false, Set("left")))),
      surfaceCamera = SurfaceCameraState("sideways", "fisheye")
    )
    val r = WorkspaceState.apply(saved, base)
    assertEquals(r.layers.map(_.representations), base.layers.map(_.representations))
    assertEquals(r.surfaceCamera, base.surfaceCamera)
  }

  test("a wrong schema id or version is rejected, not guessed") {
    val ok = WorkspaceState.encode(base)
    def schema(id: String, v: String) =
      Json.obj("id" -> Json.fromString(id), "version" -> Json.fromString(v))
    assert(WorkspaceState.decode(ok.mapObject(_.add("schema", schema("x", "1")))).isLeft)
    assert(
      WorkspaceState.decode(
        ok.mapObject(_.add("schema", schema(WorkspaceState.StateSchema, "2")))
      ).isLeft
    )
  }

  test("applying a saved view keeps the revision's recommendation and drops unknown layers") {
    val other = pub.copy(opacity = 0.2)
    val saved = Workspace(
      Vector(WorkspaceLayer("gone", other, other), WorkspaceLayer("speech-z", other, other)),
      None,
      WorkspaceLayout.default,
      "analysis"
    )
    val r = WorkspaceState.apply(saved, base)
    assertEquals(r.layers.map(_.id), Vector("speech-z", "speech-t", "b:c;d&e=f%g h"))
    assert(r.layers.forall(_.published == pub))
    assertEquals(r.layers.head.current.opacity, 0.2)
    assertEquals(r.inspector, "analysis")
  }

  test("an invalid saved layer display falls back to the recommendation") {
    val broken = pub.copy(window = Window(5, 1))
    val saved = base.copy(layers = base.layers.map(_.copy(current = broken)))
    assert(WorkspaceState.apply(saved, base).layers.forall(_.current == pub))
  }
