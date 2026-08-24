package neuropublish.viewer

/** Application-level workspace state (architecture: "WorkspaceStore owns selected result identity,
  * application layers, linked world coordinate, layout preset, and inspector state"). Pure;
  * ScalaFIM reducers own renderer state. Display values here are the user's *current* view; the
  * producer's recommendation is kept beside them so the interface can show and reset the
  * difference.
  */
final case class Window(min: Double, max: Double)

/** mode ∈ Threshold.Modes; every mode renders (Intaglio Below/Above/TwoSided). `max` is the maximum
  * magnitude: values with `|v| > max` are hidden as well.
  *
  * It exists only for `two-sided` with `min > 0`, which is exactly what the renderer can express:
  * Intaglio draws it as the outer band of a `TwoSided` threshold, whose inner band is what `min`
  * names. There is no outer band without an inner one, and `Below`/`Above` carry no outer bound at
  * all, so any other combination would be state the renderer would have to drop.
  */
final case class Threshold(mode: String, min: Double, max: Option[Double] = None)
object Threshold:
  val Modes: Set[String] = Set("two-sided", "positive", "negative", "off")

  /** Whether a maximum magnitude can be set at all: see the note on `Threshold`. */
  def boundable(t: Threshold): Boolean = t.mode == "two-sided" && t.min > 0 && t.min.isFinite

  def valid(t: Threshold): Boolean =
    Modes(t.mode) && t.min.isFinite && t.min >= 0 &&
      t.max.forall(m => boundable(t) && m.isFinite && m > t.min)

/** Colormap identifiers are a closed, URL-safe grammar here; the palette itself lives with the
  * renderer.
  */
object Colormap:
  private val Grammar = "^[a-z0-9][a-z0-9-]{0,31}$".r
  val Supported: Set[String] = Set("cold-hot", "gray", "heat", "viridis-2")

  /** Wire-level grammar. A manifest may recommend a well-formed palette a particular viewer does
    * not implement; that recommendation must be surfaced with an explicit fallback rather than
    * silently treated as one of these palettes.
    */
  def valid(id: String): Boolean = Grammar.matches(id)

  /** Palette ids this viewer can actually render. */
  def supported(id: String): Boolean = Supported(id)

final case class LayerDisplay(
    visible: Boolean,
    opacity: Double,
    window: Window,
    threshold: Threshold,
    colormap: String
)

/** Where a layer can be drawn. Identity is the result field; the field renders in every pane it has
  * a representation for and is honestly absent elsewhere (never projected). `surfaces` lists the
  * hemispheres ("left" | "right") with a surface representation. Representations are facts of the
  * revision, not presentation: a saved view or URL never changes them.
  */
final case class LayerRepresentations(volume: Boolean = true, surfaces: Set[String] = Set.empty):
  def surface: Boolean = surfaces.nonEmpty

final case class WorkspaceLayer(
    id: String, // result field id
    published: LayerDisplay,
    current: LayerDisplay,
    recommended: Boolean =
      true, // false when `published` is a data-derived default, not a producer recommendation
    representations: LayerRepresentations = LayerRepresentations()
):
  def modified: Boolean = current != published

/** The surface pane's camera as presentation state: a named viewpoint (camera direction) and a
  * projection. Viewpoints name the direction the camera looks from, so `left` shows the left
  * hemisphere's lateral face and the right hemisphere's medial face in a bilateral layout.
  */
final case class SurfaceCameraState(viewpoint: String, projection: String)
object SurfaceCameraState:
  val Viewpoints: Vector[String] =
    Vector("left", "right", "dorsal", "ventral", "anterior", "posterior")
  val Projections: Vector[String] = Vector("perspective", "orthographic")
  val default: SurfaceCameraState = SurfaceCameraState("left", "perspective")
  def valid(c: SurfaceCameraState): Boolean =
    Viewpoints.contains(c.viewpoint) && Projections.contains(c.projection)

final case class Workspace(
    layers: Vector[WorkspaceLayer], // list order: first = drawn on top; underlay excluded
    cursor: Option[(Double, Double, Double)],
    layout: WorkspaceLayout,
    inspector: String, // "layers" | "analysis" | "provenance"
    surfaceCamera: SurfaceCameraState = SurfaceCameraState.default
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
    case SetSurfaceCamera(camera: SurfaceCameraState)

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
      if Threshold.valid(t) then
        update(w, id)(_.copy(threshold = if t.mode == "off" then Threshold("off", 0.0) else t))
      else w
    case Action.SetColormap(id, c) =>
      if Colormap.supported(c) then update(w, id)(_.copy(colormap = c)) else w
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
    case Action.SetSurfaceCamera(c) =>
      if SurfaceCameraState.valid(c) then w.copy(surfaceCamera = c) else w

  /** Invariants the reducer must preserve from a valid state. */
  def isValid(w: Workspace): Boolean =
    w.layers.map(_.id).distinct.length == w.layers.length &&
      w.layers.forall { l =>
        val c = l.current
        c.opacity >= 0 && c.opacity <= 1 && c.window.min < c.window.max &&
        Threshold.valid(c.threshold) && Colormap.supported(c.colormap)
      } && w.layout.isValid && SurfaceCameraState.valid(w.surfaceCamera)
