package neuropublish.viewer

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import Workspace.Action

class WorkspaceSuite extends ScalaCheckSuite:
  private val ids = List("a", "b:c;d&e=f%g h", "c")
  private val display =
    LayerDisplay(true, 0.85, Window(-8, 8), Threshold("two-sided", 3.1), "cold-hot")
  private val reps = List(
    LayerRepresentations(volume = true, surfaces = Set("left", "right")),
    LayerRepresentations(volume = true),
    LayerRepresentations(volume = false, surfaces = Set("left"))
  )
  private val base = Workspace(
    ids.zip(reps).map((id, r) => WorkspaceLayer(id, display, display, true, r)).toVector,
    None,
    WorkspaceLayout.default,
    "layers"
  )

  private val genId = Gen.oneOf(ids)
  private val genAction: Gen[Action] = Gen.oneOf(
    genId.flatMap(id => Gen.oneOf(true, false).map(Action.SetVisible(id, _))),
    genId.flatMap(id =>
      Gen.oneOf(Gen.choose(-0.5, 1.5), Gen.const(Double.NaN)).map(Action.SetOpacity(id, _))
    ),
    genId.flatMap(id =>
      Gen.zip(Gen.choose(-20.0, 20.0), Gen.choose(-20.0, 20.0)).map((a, b) =>
        Action.SetWindow(id, Window(a, b))
      )
    ),
    genId.flatMap(id =>
      // maxima that are legal, below the minimum, and on modes that cannot carry one at all
      Gen.zip(
        Gen.oneOf("two-sided", "positive", "negative", "off", "bogus"),
        Gen.choose(-1.0, 10.0),
        Gen.option(Gen.choose(-1.0, 30.0))
      ).map((m, x, hi) => Action.SetThreshold(id, Threshold(m, x, hi)))
    ),
    genId.flatMap(id =>
      Gen.oneOf("gray", "cold-hot", "BAD;cmap", "viridis-2", "").map(Action.SetColormap(id, _))
    ),
    genId.map(Action.MoveUp.apply),
    genId.map(Action.MoveDown.apply),
    genId.map(Action.ResetLayer.apply),
    Gen.const(Action.ResetAll),
    Gen.zip(
      Gen.choose(-100.0, 100.0),
      Gen.choose(-100.0, 100.0),
      Gen.choose(-100.0, 100.0)
    ).map(Action.SetCursor.apply),
    Gen.oneOf("layers", "analysis", "provenance", "secret").map(Action.SetInspector.apply),
    Gen.oneOf(LayoutPreset.values.toSeq).map(p =>
      Action.Layout(WorkspaceLayout.Action.SetPreset(p))
    ),
    Gen.choose(-0.5, 1.5).map(f => Action.Layout(WorkspaceLayout.Action.ResizeSplit(f))),
    Gen.zip(
      Gen.oneOf(SurfaceCameraState.Viewpoints :+ "sideways"),
      Gen.oneOf(SurfaceCameraState.Projections :+ "fisheye")
    ).map((v, p) => Action.SetSurfaceCamera(SurfaceCameraState(v, p)))
  )
  given Arbitrary[Action] = Arbitrary(genAction)

  property("reducer preserves invariants and the layer set under any input") {
    forAll { (actions: List[Action]) =>
      val w = actions.foldLeft(base)(Workspace.reduce)
      Workspace.isValid(w) && w.layers.map(_.id).sorted == ids.sorted &&
      w.layers.sortBy(_.id).map(_.representations) == base.layers.sortBy(_.id).map(
        _.representations
      ) // representations are facts of the revision; no action changes them
    }
  }

  property("ResetAll returns every layer to its published display") {
    forAll { (actions: List[Action]) =>
      val w = Workspace.reduce(actions.foldLeft(base)(Workspace.reduce), Action.ResetAll)
      w.layers.forall(l => !l.modified)
    }
  }

  property(
    "URL round trip preserves order, cursor, preset, inspector, and display — with reserved characters in ids"
  ) {
    forAll { (actions: List[Action]) =>
      val w = actions.foldLeft(base)(Workspace.reduce)
      val q = ViewUrl.encode(w)
      val back = ViewUrl(q, base)
      !q.exists(c => c == ' ' || c == '#') &&
      back.layers.map(_.id) == w.layers.map(_.id) && back.inspector == w.inspector &&
      back.layout.preset == w.layout.preset && back.surfaceCamera == w.surfaceCamera &&
      math.abs(back.layout.splitFraction - w.layout.splitFraction) < 1e-4 &&
      back.layers.map(_.representations) == w.layers.map(_.representations) &&
      back.layers.zip(w.layers).forall { (x, y) =>
        x.current.visible == y.current.visible &&
        x.current.threshold.mode == y.current.threshold.mode &&
        math.abs(x.current.opacity - y.current.opacity) < 1e-4 &&
        math.abs(x.current.window.min - y.current.window.min) < 1e-4 &&
        math.abs(x.current.window.max - y.current.window.max) < 1e-4 &&
        math.abs(x.current.threshold.min - y.current.threshold.min) < 1e-4 &&
        x.current.threshold.max.zip(y.current.threshold.max).forall((a, b) =>
          math.abs(a - b) < 1e-4
        ) && x.current.threshold.max.isDefined == y.current.threshold.max.isDefined &&
        x.current.colormap == y.current.colormap
      } &&
      back.cursor.zip(w.cursor).forall((p, q) =>
        math.abs(p._1 - q._1) < 1e-4 && math.abs(p._2 - q._2) < 1e-4 && math.abs(p._3 - q._3) < 1e-4
      )
    }
  }

  test("a maximum magnitude survives the URL, and only exists where it can be rendered") {
    val bounded =
      Workspace.reduce(base, Action.SetThreshold("a", Threshold("two-sided", 3.1, Some(8.0))))
    val q = ViewUrl.encode(bounded)
    assert(q.contains("ts3.1_8"), q)
    assertEquals(ViewUrl(q, base), bounded)
    // a link written before maximum magnitude existed still reads as an unbounded threshold
    assertEquals(
      ViewUrl(q.replace("ts3.1_8", "ts3.1"), base).layers.head.current.threshold,
      Threshold("two-sided", 3.1)
    )
    // below the minimum, on a one-sided mode, or with no minimum at all: all rejected
    List(
      Threshold("two-sided", 3.1, Some(2.0)),
      Threshold("positive", 3.1, Some(8.0)),
      Threshold("two-sided", 0.0, Some(8.0))
    ).foreach(t => assertEquals(Workspace.reduce(bounded, Action.SetThreshold("a", t)), bounded))
    // changing the mode drops it rather than keeping a bound the renderer cannot honour
    assertEquals(
      Workspace.reduce(bounded, Action.SetThreshold("a", Threshold("positive", 3.1)))
        .layers.head.current.threshold,
      Threshold("positive", 3.1)
    )
  }

  test("malformed URL parts are ignored, not guessed") {
    val w = ViewUrl(
      "l=a:1:zz:-8,8:ts3.1:cold-hot;zz:1:1:0,1:off:x&c=nope&p=mars&i=secret&sc=up,down&sf=7",
      base
    )
    assertEquals(w, base)
  }

  test("surface camera and divider are encoded only when they differ from the defaults") {
    assert(!ViewUrl.encode(base).contains("sc="))
    assert(!ViewUrl.encode(base).contains("sf="))
    val w = Workspace.reduce(
      Workspace.reduce(base, Action.SetSurfaceCamera(SurfaceCameraState("dorsal", "orthographic"))),
      Action.Layout(WorkspaceLayout.Action.ResizeSplit(0.35))
    )
    val q = ViewUrl.encode(w)
    assert(q.contains("sc=dorsal,orthographic"), q)
    assert(q.contains("sf=0.35"), q)
    assertEquals(ViewUrl(q, base), w)
  }

  test("duplicate ids in the query do not duplicate layers; first wins") {
    val w = ViewUrl("l=a:0:0.5:-1,1:off:gray;a:1:1:-2,2:ts1:heat", base)
    assertEquals(w.layers.map(_.id), base.layers.map(_.id))
    assertEquals(w.layers.find(_.id == "a").map(_.current.colormap), Some("gray"))
    assert(Workspace.isValid(w))
  }
