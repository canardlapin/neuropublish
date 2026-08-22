package neuropublish.viewer

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import WorkspaceLayoutV1.Action

class WorkspaceLayoutV1Suite extends ScalaCheckSuite:
  given Arbitrary[Action] = Arbitrary(
    Gen.oneOf(
      Gen.oneOf(LayoutPreset.values.toSeq).map(Action.SetPreset.apply),
      Gen.choose(-1.0, 2.0).map(Action.ResizeNavigator.apply),
      Gen.choose(-1.0, 2.0).map(Action.ResizeInspector.apply),
      Gen.choose(-1.0, 2.0).map(Action.ResizeDrawer.apply)
    )
  )

  test("default is valid") { assert(WorkspaceLayoutV1.default.isValid) }

  property("reducer preserves validity under any action sequence") {
    forAll { (actions: List[Action]) =>
      actions.foldLeft(WorkspaceLayoutV1.default)(WorkspaceLayoutV1.reduce).isValid
    }
  }

  property("preset changes never touch pane fractions") {
    forAll(Gen.oneOf(LayoutPreset.values.toSeq)) { p =>
      val l = WorkspaceLayoutV1.reduce(WorkspaceLayoutV1.default, Action.SetPreset(p))
      l.preset == p && l.copy(preset = LayoutPreset.Volume) == WorkspaceLayoutV1.default
    }
  }
