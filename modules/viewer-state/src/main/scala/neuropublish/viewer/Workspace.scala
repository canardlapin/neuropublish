package neuropublish.viewer

/** Application-level workspace state (architecture: "WorkspaceStore owns selected result identity,
  * application layers, linked world coordinate, layout preset, and inspector state"). Pure;
  * ScalaFIM reducers own renderer state. Display values here are the user's *current* view; the
  * producer's recommendation is kept beside them so the interface can show and reset the
  * difference.
  */
final case class Window(min: Double, max: Double)

/** mode ∈ Threshold.Modes. `positive`/`negative` are carried faithfully but cannot yet be rendered
  * (upstream item).
  */
final case class Threshold(mode: String, min: Double)
object Threshold:
  val Modes: Set[String] = Set("two-sided", "positive", "negative", "off")
  val Renderable: Set[String] = Modes // every mode renders (Intaglio Below/Above/TwoSided)

/** Colormap identifiers are a closed, URL-safe grammar here; the palette itself lives with the
  * renderer.
  */
object Colormap:
  private val Grammar = "^[a-z0-9][a-z0-9-]{0,31}$".r
  def valid(id: String): Boolean = Grammar.matches(id)

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
    current: LayerDisplay,
    recommended: Boolean =
      true // false when `published` is a data-derived default, not a producer recommendation
):
  def modified: Boolean = current != published

final case class Workspace(
    layers: Vector[WorkspaceLayer], // list order: first = drawn on top; underlay excluded
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

  /** Invalid inputs leave the state unchanged (callers may report that via `tryDispatch`). */
  def reduce(w: Workspace, a: Action): Workspace = a match
    case Action.SetVisible(id, v) => update(w, id)(_.copy(visible = v))
    case Action.SetOpacity(id, o) =>
      if o.isNaN then w else update(w, id)(_.copy(opacity = clamp01(o)))
    case Action.SetWindow(id, win) =>
      if win.min.isFinite && win.max.isFinite && win.min < win.max then
        update(w, id)(_.copy(window = win))
      else w
    case Action.SetThreshold(id, t) =>
      if t.min.isFinite && t.min >= 0 && Threshold.Modes(t.mode) then
        update(w, id)(_.copy(threshold = if t.mode == "off" then Threshold("off", 0.0) else t))
      else w
    case Action.SetColormap(id, c) =>
      if Colormap.valid(c) then update(w, id)(_.copy(colormap = c)) else w
    case Action.MoveUp(id) =>
      val i = w.layers.indexWhere(_.id == id); w.copy(layers = swap(w.layers, i, i - 1))
    case Action.MoveDown(id) =>
      val i = w.layers.indexWhere(_.id == id); w.copy(layers = swap(w.layers, i, i + 1))
    case Action.ResetLayer(id) =>
      w.copy(layers = w.layers.map(l => if l.id == id then l.copy(current = l.published) else l))
    case Action.ResetAll => w.copy(layers = w.layers.map(l => l.copy(current = l.published)))
    case Action.SetCursor(x, y, z) =>
      if x.isFinite && y.isFinite && z.isFinite then w.copy(cursor = Some((x, y, z))) else w
    case Action.SetInspector(t) =>
      if Set("layers", "analysis", "provenance")(t) then w.copy(inspector = t) else w
    case Action.Layout(la) => w.copy(layout = WorkspaceLayout.reduce(w.layout, la))

  /** Invariants the reducer must preserve from a valid state. */
  def isValid(w: Workspace): Boolean =
    w.layers.map(_.id).distinct.length == w.layers.length &&
      w.layers.forall { l =>
        val c = l.current
        c.opacity >= 0 && c.opacity <= 1 && c.window.min < c.window.max && c.threshold.min >= 0 &&
        Threshold.Modes(c.threshold.mode) && Colormap.valid(c.colormap)
      } && w.layout.isValid
