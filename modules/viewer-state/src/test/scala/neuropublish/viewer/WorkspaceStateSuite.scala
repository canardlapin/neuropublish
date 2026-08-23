package neuropublish.viewer

import io.circe.Json
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

class WorkspaceStateSuite extends ScalaCheckSuite:
  private val ids = List("speech-t", "speech-z", "b:c;d&e=f%g h")
  private val pub = LayerDisplay(true, 0.85, Window(-8, 8), Threshold("two-sided", 3.1), "cold-hot")
  private val base = Workspace(
    ids.map(id => WorkspaceLayer(id, pub, pub)).toVector,
    None,
    WorkspaceLayout.default,
    "layers"
  )

  private val genDisplay: Gen[LayerDisplay] =
    for
      v <- Gen.oneOf(true, false)
      o <- Gen.choose(0.0, 1.0)
      lo <- Gen.choose(-20.0, 0.0)
      hi <- Gen.choose(0.5, 20.0)
      m <- Gen.oneOf(Threshold.Modes.toList)
      t <- Gen.choose(0.0, 10.0)
      c <- Gen.oneOf("cold-hot", "viridis", "hot")
    yield LayerDisplay(
      v,
      o,
      Window(lo, hi),
      if m == "off" then Threshold("off", 0.0) else Threshold(m, t),
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
      drawer <- Gen.choose(0.1, 0.8)
      tab <- Gen.oneOf("layers", "analysis", "provenance")
    yield Workspace(
      order.zip(displays).map((id, d) => WorkspaceLayer(id, pub, d)),
      cursor,
      WorkspaceLayout(preset, nav, ins, drawer),
      tab
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
