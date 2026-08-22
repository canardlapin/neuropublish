package neuropublish.viewer.laminar

import org.scalajs.dom
import scala.scalajs.js
import scalafim.surface.view.three.{ThreeCanvasSize, ThreeJsRuntime}

/** Hosts a ScalaFIM Three.js surface runtime; `three` is the namespace object supplied by the app.
  */
final class SurfaceHost(three: js.Dynamic, val probe: LifecycleProbe)
    extends RendererHost[SurfaceHost.Live]:
  import SurfaceHost.Live

  def create(canvas: dom.html.Canvas): Live =
    val rt = ThreeJsRuntime
      .create(three, canvas.asInstanceOf[js.Dynamic])
      .fold(e => throw IllegalStateException(e.toString), identity)
    val onLost: js.Function1[dom.Event, Unit] = _ => probe.contextLost += 1
    canvas.addEventListener("webglcontextlost", onLost)
    probe.created += 1
    probe.lastContextState = rt.contextState.toString
    Live(canvas, rt, onLost)

  def resize(r: Live, w: Int, h: Int): Unit =
    if w > 0 && h > 0 && !r.disposed then
      r.runtime
        .resize(ThreeCanvasSize.unsafe(w, h, dom.window.devicePixelRatio))
        .left.foreach(e => probe.errors += e.toString)
      r.runtime.draw().left.foreach(e => probe.errors += e.toString)
      probe.resizes += 1
      probe.lastContextState = r.runtime.contextState.toString

  /** Idempotent. */
  def dispose(r: Live): Unit =
    if !r.disposed then
      r.disposed = true
      r.canvas.removeEventListener("webglcontextlost", r.onLost)
      r.runtime.dispose().left.foreach(e => probe.errors += e.toString)
      // Three's dispose() releases GPU objects but leaves the context alive until
      // the canvas is garbage collected; browsers count it against their live
      // context budget until then. Release it deterministically.
      forceContextLoss(r.canvas)
      probe.disposed += 1

  def contextState(r: Live): String = r.runtime.contextState.toString

  private def forceContextLoss(canvas: dom.html.Canvas): Unit =
    val dyn = canvas.asInstanceOf[js.Dynamic]
    val ctx = dyn.getContext("webgl2")
    val gl = if js.isUndefined(ctx) || ctx == null then dyn.getContext("webgl") else ctx
    if !(js.isUndefined(gl) || gl == null) then
      val ext = gl.getExtension("WEBGL_lose_context")
      if !(js.isUndefined(ext) || ext == null) then ext.loseContext()

object SurfaceHost:
  final class Live(
      val canvas: dom.html.Canvas,
      val runtime: ThreeJsRuntime,
      val onLost: js.Function1[dom.Event, Unit]
  ):
    var disposed = false
