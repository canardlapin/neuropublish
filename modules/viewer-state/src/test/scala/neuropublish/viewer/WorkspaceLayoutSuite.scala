package neuropublish.viewer

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import WorkspaceLayout.Action

class WorkspaceLayoutSuite extends ScalaCheckSuite:
  given Arbitrary[Action] = Arbitrary(
    Gen.oneOf(
      Gen.oneOf(LayoutPreset.values.toSeq).map(Action.SetPreset.apply),
      Gen.choose(-1.0, 2.0).map(Action.ResizeNavigator.apply),
      Gen.choose(-1.0, 2.0).map(Action.ResizeInspector.apply),
      Gen.oneOf(Gen.choose(-1.0, 2.0), Gen.const(Double.NaN)).map(Action.ResizeSplit.apply)
    )
  )

  test("default is valid") { assert(WorkspaceLayout.default.isValid) }

  property("reducer preserves validity under any action sequence") {
    forAll { (actions: List[Action]) =>
      actions.foldLeft(WorkspaceLayout.default)(WorkspaceLayout.reduce).isValid
    }
  }

  property("preset changes never touch pane fractions") {
    forAll(Gen.oneOf(LayoutPreset.values.toSeq)) { p =>
      val l = WorkspaceLayout.reduce(WorkspaceLayout.default, Action.SetPreset(p))
      l.preset == p && l.copy(preset = LayoutPreset.Volume) == WorkspaceLayout.default
    }
  }

  property("a dragged split fraction survives the URL exactly: decode(encode(w)) == w") {
    forAll(Gen.choose(-0.5, 1.5)) { f =>
      val base = Workspace(Vector.empty, None, WorkspaceLayout.default, "layers")
      val dragged = Workspace.reduce(base, Workspace.Action.Layout(Action.ResizeSplit(f)))
      val back = ViewUrl(ViewUrl.encode(dragged), base)
      back == dragged && ViewUrl.encode(back) == ViewUrl.encode(dragged)
    }
  }
