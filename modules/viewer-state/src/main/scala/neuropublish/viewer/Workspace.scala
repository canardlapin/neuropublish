package neuropublish.viewer

/** Application-level workspace state (architecture: "WorkspaceStore owns selected result identity,
  * application layers, linked world coordinate, layout preset, and inspector state"). Pure;
  * ScalaFIM reducers own renderer state. Display values here are the user's *current* view; the
  * producer's recommendation is kept beside them so the interface can show and reset the
  * difference.
  */
final case class Window(min: Double, max: Double)
final case class Threshold(
    mode: String,
    min: Double
) // mode: "two-sided" | "off" (Stage 3); positive/negative need upstream work

final case class LayerDisplay(
    visible: Boolean,
    opacity: Double,
    window: Window,
    threshold: Threshold,
    colormap: String
)

final case class WorkspaceLayer(
    id: String, // result field id
    published: LayerDisplay,
    current: LayerDisplay
):
  def modified: Boolean = current != published

final case class Workspace(
    layers: Vector[WorkspaceLayer], // draw order, underlay excluded
    cursor: Option[(Double, Double, Double)],
    layout: WorkspaceLayout,
    inspector: String // "layers" | "analysis" | "provenance"
)

object Workspace:
  enum Action:
    case SetVisible(layer: String, visible: Boolean)
    case SetOpacity(layer: String, opacity: Double)
    case SetWindow(layer: String, window: Window)
    case SetThreshold(layer: String, threshold: Threshold)
    case SetColormap(layer: String, colormap: String)
    case MoveUp(layer: String)
    case MoveDown(layer: String)
    case ResetLayer(layer: String)
    case ResetAll
    case SetCursor(x: Double, y: Double, z: Double)
    case SetInspector(tab: String)
    case Layout(action: WorkspaceLayout.Action)

  private def clamp01(x: Double) = math.min(1.0, math.max(0.0, x))

  private def update(w: Workspace, id: String)(f: LayerDisplay => LayerDisplay): Workspace =
    w.copy(layers = w.layers.map(l => if l.id == id then l.copy(current = f(l.current)) else l))

  private def swap(v: Vector[WorkspaceLayer], i: Int, j: Int) =
    if i < 0 || j < 0 || i >= v.length || j >= v.length then v
    else v.updated(i, v(j)).updated(j, v(i))

  def reduce(w: Workspace, a: Action): Workspace = a match
    case Action.SetVisible(id, v) => update(w, id)(_.copy(visible = v))
    case Action.SetOpacity(id, o) => update(w, id)(_.copy(opacity = clamp01(o)))
    case Action.SetWindow(id, win) =>
      if win.min < win.max then update(w, id)(_.copy(window = win)) else w
    case Action.SetThreshold(id, t) =>
      if t.min >= 0 then update(w, id)(_.copy(threshold = t)) else w
    case Action.SetColormap(id, c) => update(w, id)(_.copy(colormap = c))
    case Action.MoveUp(id) =>
      val i = w.layers.indexWhere(_.id == id); w.copy(layers = swap(w.layers, i, i - 1))
    case Action.MoveDown(id) =>
      val i = w.layers.indexWhere(_.id == id); w.copy(layers = swap(w.layers, i, i + 1))
    case Action.ResetLayer(id) =>
      w.copy(layers = w.layers.map(l => if l.id == id then l.copy(current = l.published) else l))
    case Action.ResetAll => w.copy(layers = w.layers.map(l => l.copy(current = l.published)))
    case Action.SetCursor(x, y, z) => w.copy(cursor = Some((x, y, z)))
    case Action.SetInspector(t) => w.copy(inspector = t)
    case Action.Layout(la) => w.copy(layout = WorkspaceLayout.reduce(w.layout, la))

  /** Invariants the reducer must preserve from a valid state. */
  def isValid(w: Workspace): Boolean =
    w.layers.map(_.id).distinct.length == w.layers.length &&
      w.layers.forall(l =>
        l.current.opacity >= 0 && l.current.opacity <= 1 &&
          l.current.window.min < l.current.window.max && l.current.threshold.min >= 0
      ) &&
      w.layout.isValid
