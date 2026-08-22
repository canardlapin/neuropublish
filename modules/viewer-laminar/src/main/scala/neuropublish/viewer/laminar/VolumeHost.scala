package neuropublish.viewer.laminar

import intaglio.DeviceContext
import intaglio.canvas.{CanvasRasterFactory, CanvasRenderingContext2D}
import org.scalajs.dom
import scalafim.image.view.{ViewerAction, ViewerModel, ViewerSession}
import scalafim.image.view.canvas.{CanvasViewerController, CanvasViewerHost}

/** Hosts a ScalaFIM Canvas volume controller; renders on animation frames it tracks and cancels. */
final class VolumeHost(model: ViewerModel, initial: ViewerSession, val probe: LifecycleProbe)
    extends RendererHost[VolumeHost.Live]:
  import VolumeHost.Live

  def create(canvas: dom.html.Canvas): Live =
    val controller = CanvasViewerHost.controller(model, initial)
      .fold(e => throw IllegalStateException(e.toString), identity)
    val ctx = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    probe.created += 1
    Live(canvas, ctx, controller)

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
      val controller: CanvasViewerController
  ):
    var raf: Option[Int] = None

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
