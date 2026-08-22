package neuropublish.viewer.laminar

import intaglio.DeviceContext
import intaglio.canvas.{CanvasRasterFactory, CanvasRenderingContext2D}
import org.scalajs.dom
import scalafim.image.AnatomicalPlane
import scalafim.image.view.{PanelReadout, ViewerAction, ViewerModel, ViewerSession, ViewerState}
import scalafim.image.view.canvas.{CanvasViewerController, CanvasViewerHost}

/** Hosts a ScalaFIM Canvas volume controller; renders on animation frames it tracks and cancels.
  * The model may be replaced (layer reorder needs a new `ViewerModel`); the viewer state (cursor,
  * panel views, per-layer presentation) is carried across the rebuild.
  */
final class VolumeHost(initialModel: ViewerModel, initial: ViewerSession, val probe: LifecycleProbe)
    extends RendererHost[VolumeHost.Live]:
  import VolumeHost.Live

  def create(canvas: dom.html.Canvas): Live =
    val controller = CanvasViewerHost
      .controller(initialModel, initial)
      .fold(e => throw IllegalStateException(e.toString), identity)
    val ctx = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    probe.created += 1
    Live(canvas, ctx, controller, initialModel)

  /** Replace the model, keeping cursor, views, and per-layer presentation for layers that still
    * exist.
    */
  def rebuild(r: Live, model: ViewerModel): Unit =
    if !r.controller.isClosed then
      val session = r.controller.session.toOption.getOrElse(initial)
      val keep = model.layers.map(_.id).toSet
      val state = session.state.copy(layerPresentation =
        session.state.layerPresentation.filter((id, _) => keep(id))
      )
      r.controller.close()
      r.controller = CanvasViewerHost
        .controller(model, session.copy(state = state))
        .fold(e => throw IllegalStateException(e.toString), identity)
      r.model = model
      scheduleRender(r)

  def resize(r: Live, w: Int, h: Int): Unit =
    if w > 0 && h > 0 && !r.controller.isClosed then
      val dpr = dom.window.devicePixelRatio
      val pw = math.max(1, math.round(w * dpr).toInt)
      val ph = math.max(1, math.round(h * dpr).toInt)
      r.canvas.width = pw; r.canvas.height = ph
      r.controller
        .dispatch(ViewerAction.Resize(DeviceContext.unsafe(pw.toDouble, ph.toDouble)))
        .left.foreach(e => probe.errors += e.toString)
      probe.resizes += 1
      scheduleRender(r)

  /** Dispatch a typed action and schedule a redraw; never rereads source assets. */
  def dispatch(r: Live, a: ViewerAction): Unit =
    if !r.controller.isClosed then
      r.controller.dispatch(a).left.foreach(e => probe.errors += e.toString)
      scheduleRender(r)
      a match
        case _: ViewerAction.SetCursor => r.onCursor.foreach(_())
        case _ => ()

  /** Pick at canvas CSS-pixel coordinates (scaled by devicePixelRatio internally). */
  def pick(r: Live, cssX: Double, cssY: Double): Unit =
    if !r.controller.isClosed then
      val dpr = dom.window.devicePixelRatio
      r.controller.pick(cssX * dpr, cssY * dpr).left.foreach(e => probe.errors += e.toString)
      scheduleRender(r)
      r.onCursor.foreach(_())

  def scroll(r: Live, cssX: Double, cssY: Double, steps: Int): Unit =
    if !r.controller.isClosed then
      val dpr = dom.window.devicePixelRatio
      r.controller.scroll(cssX * dpr, cssY * dpr, steps).left.foreach(e =>
        probe.errors += e.toString
      )
      scheduleRender(r)
      r.onCursor.foreach(_())

  /** Current readouts (world cursor and per-layer sample) for one plane, from the viewer's own
    * frame.
    */
  def readout(r: Live, plane: AnatomicalPlane = AnatomicalPlane.Axial): Option[PanelReadout] =
    if r.controller.isClosed then None
    else r.controller.session.toOption.flatMap(_.frame(r.model).toOption).map(_.readouts(plane))

  def state(r: Live): Option[ViewerState] = r.controller.session.toOption.map(_.state)

  /** Schedule one render for the next animation frame (coalesced). */
  def requestRender(r: Live): Unit = scheduleRender(r)

  private def scheduleRender(r: Live): Unit =
    if r.raf.isEmpty then
      val id = dom.window.requestAnimationFrame { _ =>
        r.raf = None
        probe.rafOutstanding -= 1
        if !r.controller.isClosed then
          given CanvasRasterFactory = CanvasRasterFactory.browser
          r.controller.render(r.ctx).left.foreach(e => probe.errors += e.toString)
          probe.frames += 1
      }
      r.raf = Some(id)
      probe.rafOutstanding += 1

  /** Idempotent: a second call finds the controller closed and nothing pending. */
  def dispose(r: Live): Unit =
    r.raf.foreach { id => dom.window.cancelAnimationFrame(id); probe.rafOutstanding -= 1 }
    r.raf = None
    if !r.controller.isClosed then
      r.controller.close()
      probe.disposed += 1

object VolumeHost:
  final class Live(
      val canvas: dom.html.Canvas,
      val ctx: CanvasRenderingContext2D,
      var controller: CanvasViewerController,
      var model: ViewerModel
  ):
    var raf: Option[Int] = None

    /** Called after a pick or cursor change (readouts are recomputed then, not per frame). */
    var onCursor: Option[() => Unit] = None

/** Mutable counters a browser test reads back; deliberately plain. */
final class LifecycleProbe:
  var created = 0
  var disposed = 0
  var resizes = 0
  var frames = 0
  var rafOutstanding = 0
  var lastContextState = "n/a"
  var contextLost = 0
  val errors = scala.collection.mutable.ListBuffer.empty[String]
