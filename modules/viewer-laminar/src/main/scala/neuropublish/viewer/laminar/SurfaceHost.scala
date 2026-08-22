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
      // ThreeJsRuntime.dispose() now forces WEBGL_lose_context itself (ScalaFIM bd-01M0NS3EFB…)
      probe.disposed += 1

  def contextState(r: Live): String = r.runtime.contextState.toString

object SurfaceHost:
  final class Live(
      val canvas: dom.html.Canvas,
      val runtime: ThreeJsRuntime,
      val onLost: js.Function1[dom.Event, Unit]
  ):
    var disposed = false
