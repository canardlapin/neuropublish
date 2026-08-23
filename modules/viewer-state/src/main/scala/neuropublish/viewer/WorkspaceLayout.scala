package neuropublish.viewer

/** Layout presets committed by ADR 0002. Compare is a later extension. */
enum LayoutPreset:
  case Volume, Surface, Hybrid

/** Workspace arrangement: preset plus pane proportions. This is the domain type; the saved-view
  * wire record carries it as the open record `org.neuropublish.view/workspace-layout@1` (ADR 0001),
  * so versioning lives in the schema reference, not in Scala type names. Fractions are of the total
  * width for the left/right inspectors and of the centre height for the lower drawer; all in (0,
  * 1).
  */
final case class WorkspaceLayout(
    preset: LayoutPreset,
    navigatorFraction: Double,
    inspectorFraction: Double,
    drawerFraction: Double,
    splitFraction: Double = 0.5 // Hybrid: the volume pane's share of the centre width
):
  def isValid: Boolean =
    List(navigatorFraction, inspectorFraction, drawerFraction, splitFraction).forall(f =>
      f > 0.0 && f < 1.0
    ) && navigatorFraction + inspectorFraction < 1.0

object WorkspaceLayout:
  val default: WorkspaceLayout = WorkspaceLayout(LayoutPreset.Volume, 0.18, 0.25, 0.3, 0.5)

  enum Action:
    case SetPreset(preset: LayoutPreset)
    case ResizeNavigator(fraction: Double)
    case ResizeInspector(fraction: Double)
    case ResizeDrawer(fraction: Double)
    case ResizeSplit(fraction: Double)

  private def clamp(f: Double) = math.min(0.9, math.max(0.05, f))

  /** Pure reducer; never produces an invalid layout from a valid one. */
  def reduce(l: WorkspaceLayout, a: Action): WorkspaceLayout = a match
    case Action.SetPreset(p) => l.copy(preset = p)
    case Action.ResizeNavigator(f) =>
      val nf = math.min(clamp(f), 0.95 - l.inspectorFraction)
      l.copy(navigatorFraction = nf)
    case Action.ResizeInspector(f) =>
      val nf = math.min(clamp(f), 0.95 - l.navigatorFraction)
      l.copy(inspectorFraction = nf)
    case Action.ResizeDrawer(f) => l.copy(drawerFraction = clamp(f))
    case Action.ResizeSplit(f) => if f.isNaN then l else l.copy(splitFraction = clamp(f))
