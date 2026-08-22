package neuropublish.viewer

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import Workspace.Action

class WorkspaceSuite extends ScalaCheckSuite:
  private val ids = List("a", "b", "c")
  private val display =
    LayerDisplay(true, 0.85, Window(-8, 8), Threshold("two-sided", 3.1), "cold-hot")
  private val base = Workspace(
    ids.map(id => WorkspaceLayer(id, display, display)).toVector,
    None,
    WorkspaceLayout.default,
    "layers"
  )

  private val genId = Gen.oneOf(ids)
  private val genDisplayAction: Gen[Action] = Gen.oneOf(
    genId.flatMap(id => Gen.oneOf(true, false).map(Action.SetVisible(id, _))),
    genId.flatMap(id => Gen.choose(-0.5, 1.5).map(Action.SetOpacity(id, _))),
    genId.flatMap(id =>
      Gen.zip(Gen.choose(-20.0, 20.0), Gen.choose(-20.0, 20.0)).map((a, b) =>
        Action.SetWindow(id, Window(a, b))
      )
    ),
    genId.flatMap(id =>
      Gen.choose(-1.0, 10.0).map(m => Action.SetThreshold(id, Threshold("two-sided", m)))
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
    Gen.oneOf("layers", "analysis", "provenance").map(Action.SetInspector.apply)
  )
  given Arbitrary[Action] = Arbitrary(genDisplayAction)

  property("reducer preserves invariants and the layer set") {
    forAll { (actions: List[Action]) =>
      val w = actions.foldLeft(base)(Workspace.reduce)
      Workspace.isValid(w) && w.layers.map(_.id).sorted == ids.sorted
    }
  }

  property("ResetAll returns every layer to its published display") {
    forAll { (actions: List[Action]) =>
      val w = Workspace.reduce(actions.foldLeft(base)(Workspace.reduce), Action.ResetAll)
      w.layers.forall(l => !l.modified)
    }
  }

  property("URL round trip preserves order, cursor, preset, inspector, and display") {
    forAll { (actions: List[Action]) =>
      val w = actions.foldLeft(base)(Workspace.reduce)
      val back = ViewUrl(ViewUrl.encode(w), base)
      val same = back.layers.map(_.id) == w.layers.map(_.id) && back.inspector == w.inspector &&
        back.layout.preset == w.layout.preset &&
        back.layers.zip(w.layers).forall { (x, y) =>
          x.current.visible == y.current.visible &&
          x.current.threshold.mode == y.current.threshold.mode &&
          math.abs(x.current.opacity - y.current.opacity) < 1e-4 &&
          math.abs(x.current.window.min - y.current.window.min) < 1e-4 &&
          math.abs(x.current.window.max - y.current.window.max) < 1e-4 &&
          math.abs(x.current.threshold.min - y.current.threshold.min) < 1e-4 &&
          x.current.colormap == y.current.colormap
        } && back.cursor.zip(w.cursor).forall((p, q) =>
          math.abs(p._1 - q._1) < 1e-4 && math.abs(p._2 - q._2) < 1e-4 &&
            math.abs(p._3 - q._3) < 1e-4
        )
      same
    }
  }

  test("malformed URL parts are ignored, not guessed") {
    val w = ViewUrl("l=a:1:zz:-8,8:ts3.1:cold-hot;zz:1:1:0,1:off:x&c=nope&p=mars&i=secret", base)
    assertEquals(w, base)
  }
