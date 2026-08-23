package neuropublish.viewer.laminar

import org.scalajs.dom
import scala.scalajs.js
import scalafim.image.WorldPoint
import scalafim.surface.VertexId
import scalafim.surface.view.*
import scalafim.surface.view.three.{ThreeCanvasSize, ThreeJsRuntime, ThreeSurfaceBackend}

/** Hosts a ScalaFIM surface viewer on a Three.js runtime: owns the `ThreeJsRuntime` and
  * `ThreeSurfaceBackend`, a `SurfaceViewerModel` with its reducer state, and renders the compiled
  * `SurfaceRenderPlan` on coalesced animation frames (tracked like `VolumeHost`). `three` is the
  * Three.js namespace object supplied by the application.
  *
  * The model may be absent (a revision with no surface geometry): the pane then holds a live
  * context that draws nothing, so the lifecycle contract is identical with or without data.
  *
  * The host outlives its panes: a preset switch disposes the live handle and mounts a new one. The
  * last reducer state is kept as `snapshot` on dispose and carried into the next `create`, so
  * zoom, orbit, lighting, and the selection survive Volume → Hybrid → Surface switches.
  */
final class SurfaceHost(
    three: js.Dynamic,
    initialModel: Option[SurfaceViewerModel],
    val probe: LifecycleProbe,
    linkRadius: SurfaceLinkRadius = SurfaceHost.DefaultLinkRadius
) extends RendererHost[SurfaceHost.Live]:
  import SurfaceHost.*

  private var lastState: Option[SurfaceViewerState] = None

  /** The reducer state of the last disposed pane, if any (what the next mount starts from). */
  def snapshot: Option[SurfaceViewerState] = lastState

  def create(canvas: dom.html.Canvas): Live =
    val rt = ThreeJsRuntime
      .create(three, canvas.asInstanceOf[js.Dynamic])
      .fold(e => throw IllegalStateException(e.toString), identity)
    val backend = ThreeSurfaceBackend
      .create(rt)
      .fold(e => throw IllegalStateException(e.toString), identity)
    val onLost: js.Function1[dom.Event, Unit] = _ => probe.contextLost += 1
    canvas.addEventListener("webglcontextlost", onLost)
    probe.created += 1
    probe.lastContextState = rt.contextState.toString
    val live = Live(canvas, rt, backend, onLost)
    initialModel.foreach { m =>
      val v = Viewer(m, SurfaceViewerState.initial(m))
      lastState.foreach(carry(v, _))
      live.viewer = Some(v)
    }
    live

  /** Replace the model (a colormap or layer-set change needs new `SurfaceLayer`s). Layout, camera,
    * lighting, selection, and per-layer presentation are carried across for what still exists.
    */
  def rebuild(r: Live, model: Option[SurfaceViewerModel]): Unit =
    if !r.disposed then
      val previous = r.viewer
      r.viewer = model.map(m => Viewer(m, SurfaceViewerState.initial(m)))
      for
        v <- r.viewer
        p <- previous
      do carry(v, p.state)
      r.plan = None
      scheduleRender(r)

  /** Re-apply an earlier state onto a fresh viewer as actions. A carried action the model rejects
    * (e.g. a layout naming a surface that is gone) is dropped, not reported: the initial state is
    * already valid.
    */
  private def carry(v: Viewer, old: SurfaceViewerState): Unit =
    val carried: Vector[SurfaceViewerAction] =
      Vector(
        SurfaceViewerAction.SetLayout(old.layout),
        SurfaceViewerAction.SetViewpoint(old.camera.viewpoint),
        SurfaceViewerAction.SetProjection(old.camera.projection),
        SurfaceViewerAction.SetZoom(old.camera.zoom),
        SurfaceViewerAction.SetOrbit(old.camera.orbit),
        SurfaceViewerAction.SetLighting(old.lighting)
      ) ++ old.selection.toVector.map(s => SurfaceViewerAction.Select(s.surface, s.vertex)) ++
        old.presentations.values.toVector.filter(pr => v.model.layer(pr.id).isDefined).flatMap {
          pr =>
            Vector(
              SurfaceViewerAction.SetLayerVisible(pr.id, pr.visible),
              SurfaceViewerAction.SetLayerOpacity(pr.id, pr.opacity)
            ) ++ pr.window.map(SurfaceViewerAction.SetLayerWindow(pr.id, _)) ++
              pr.threshold.map(SurfaceViewerAction.SetLayerThreshold(pr.id, _))
        }
    carried.foreach(a => SurfaceViewer.reduce(v.model, v.state, a).foreach(s => v.state = s))

  def resize(r: Live, w: Int, h: Int): Unit =
    if w > 0 && h > 0 && !r.disposed then
      r.size = ThreeCanvasSize.unsafe(w, h, dom.window.devicePixelRatio)
      if r.viewer.isEmpty then
        // nothing to compile: keep the canvas sized and cleared
        r.runtime.resize(r.size).left.foreach(e => probe.errors += e.toString)
        r.runtime.draw().left.foreach(e => probe.errors += e.toString)
      probe.resizes += 1
      probe.lastContextState = r.runtime.contextState.toString
      scheduleRender(r)

  /** Dispatch a typed ScalaFIM action; a rejected action is recorded on the probe. */
  def dispatch(r: Live, a: SurfaceViewerAction): Unit =
    if !r.disposed then
      r.viewer.foreach { v =>
        SurfaceViewer.reduce(v.model, v.state, a) match
          case Right(s) => v.state = s
          case Left(e) => probe.errors += e.toString
      }
      scheduleRender(r)

  def model(r: Live): Option[SurfaceViewerModel] = r.viewer.map(_.model)
  def state(r: Live): Option[SurfaceViewerState] = r.viewer.map(_.state)

  /** Pick at canvas CSS-pixel coordinates. The world point is the picked vertex's own position
    * through the surface's `surfaceToWorld`, so a surface pick and a later `setCursor` at that
    * point link back to the same vertex at distance 0.
    */
  def pick(r: Live, cssX: Double, cssY: Double): Option[Pick] =
    if r.disposed then None
    else
      r.backend.pick(cssX, cssY) match
        case Left(e) => probe.errors += e.toString; None
        case Right(hit) =>
          hit.flatMap { p =>
            r.viewer.flatMap(_.model.surface(p.surface)).flatMap { asset =>
              SurfaceWorldLink.worldPoint(asset.geometry, VertexId(p.vertex)).toOption.map(w =>
                Pick(p.surface, p.vertex, w)
              )
            }
          }

  /** Link the shared world cursor to the nearest vertex (over every surface of the model) within
    * `linkRadius`. The result is explicit: the pane never pretends a far-away vertex is "the"
    * cursor. A link selects the vertex; out of range clears the selection.
    */
  def setCursor(r: Live, world: WorldPoint): Link =
    if r.disposed then Link.NoGeometry
    else
      r.viewer match
        case None => Link.NoGeometry
        case Some(v) if v.model.surfaces.isEmpty => Link.NoGeometry
        case Some(v) =>
          def geometryError(e: SurfaceViewError): Link =
            // a permanently bad geometry would otherwise report on every cursor move
            if !probe.errors.lastOption.contains(e.toString) then probe.errors += e.toString
            Link.NoGeometry
          val candidates = v.model.surfaces.map { asset =>
            SurfaceWorldLink.nearestVertex(asset.id, asset.geometry, world, linkRadius) match
              case Right(sel) =>
                // the distance is measured, never assumed: a vertex whose world position cannot
                // be computed is not a link (nearestVertex already rejects such a transform, so
                // this branch is a guard, not a path)
                SurfaceWorldLink.worldPoint(asset.geometry, sel.vertex) match
                  case Right(p) => Link.Linked(asset.id, sel.vertex.index, dist(p, world))
                  case Left(e) => geometryError(e)
              case Left(SurfaceViewError.LinkDistanceExceeded(d, _)) => Link.OutOfRange(d)
              case Left(e) => geometryError(e)
          }
          val best = candidates.minBy {
            case Link.Linked(_, _, d) => (0, d)
            case Link.OutOfRange(d) => (1, d)
            case Link.NoGeometry => (2, Double.PositiveInfinity)
          }
          // only a changed selection schedules a frame: a cursor re-sent at the same vertex (a
          // remount, a URL restore) must not cost a render
          val wanted = best match
            case Link.Linked(s, vtx, _) => Some(SurfaceSelection(s, VertexId(vtx)))
            case _ => None
          if wanted != v.state.selection then
            dispatch(
              r,
              wanted.fold(SurfaceViewerAction.ClearSelection)(s =>
                SurfaceViewerAction.Select(s.surface, s.vertex)
              )
            )
          best

  /** The readout of the current selection from the last compiled plan (values at the vertex, in
    * layer order), recomputed per frame by the compiler, not per pick.
    */
  def readout(r: Live): Option[SurfaceReadout] =
    r.plan.flatMap(_.readouts.headOption)

  def requestRender(r: Live): Unit = scheduleRender(r)

  private def scheduleRender(r: Live): Unit =
    if r.raf.isEmpty && !r.disposed then
      val id = dom.window.requestAnimationFrame { _ =>
        r.raf = None
        probe.rafOutstanding -= 1
        if !r.disposed then
          r.viewer.foreach { v =>
            SurfaceCompiler.compile(v.model, v.state) match
              case Left(e) => probe.errors += e.toString
              case Right(plan) =>
                r.plan = Some(plan)
                r.backend.render(plan, r.size).left.foreach(e => probe.errors += e.toString)
                probe.frames += 1
                r.onFrame.foreach(_())
          }
          probe.lastContextState = r.runtime.contextState.toString
      }
      r.raf = Some(id)
      probe.rafOutstanding += 1

  /** Idempotent: cancels the pending frame, keeps the reducer state as the next mount's starting
    * point, disposes the backend (`ThreeSurfaceBackend.dispose` → `ThreeJsRuntime.dispose`, which
    * forces WebGL context loss through `WEBGL_lose_context`), and removes the listener exactly
    * once.
    */
  def dispose(r: Live): Unit =
    r.raf.foreach { id => dom.window.cancelAnimationFrame(id); probe.rafOutstanding -= 1 }
    r.raf = None
    if !r.disposed then
      r.disposed = true
      r.viewer.foreach(v => lastState = Some(v.state))
      r.canvas.removeEventListener("webglcontextlost", r.onLost)
      r.backend.dispose().left.foreach(e => probe.errors += e.toString)
      r.viewer = None
      r.plan = None
      probe.disposed += 1

  def contextState(r: Live): String = r.runtime.contextState.toString

  private def dist(a: WorldPoint, b: WorldPoint): Double =
    val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
    math.sqrt(dx * dx + dy * dy + dz * dz)

object SurfaceHost:
  /** The product's linking tolerance: "no vertex within 3 mm". */
  val DefaultLinkRadius: SurfaceLinkRadius = SurfaceLinkRadius.unsafe(3.0)

  final case class Pick(surface: SurfaceId, vertex: Int, world: WorldPoint)

  /** Result of linking the world cursor to a surface. */
  enum Link:
    case Linked(surface: SurfaceId, vertex: Int, distance: Double)
    case OutOfRange(distance: Double)
    case NoGeometry

  final class Viewer(val model: SurfaceViewerModel, var state: SurfaceViewerState)

  final class Live(
      val canvas: dom.html.Canvas,
      val runtime: ThreeJsRuntime,
      val backend: ThreeSurfaceBackend,
      val onLost: js.Function1[dom.Event, Unit]
  ):
    var disposed = false
    var viewer: Option[Viewer] = None
    var plan: Option[SurfaceRenderPlan] = None
    var size: ThreeCanvasSize = ThreeCanvasSize.unsafe(1, 1, 1.0)
    var raf: Option[Int] = None

    /** Called after each rendered frame (readouts come from the compiled plan, so they are fresh
      * only then).
      */
    var onFrame: Option[() => Unit] = None
