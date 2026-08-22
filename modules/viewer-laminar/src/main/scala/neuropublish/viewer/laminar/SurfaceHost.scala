package neuropublish.viewer.laminar

import org.scalajs.dom
import scala.scalajs.js
import scalafim.surface.view.three.{ThreeCanvasSize, ThreeJsRuntime}

/** Hosts a ScalaFIM Three.js surface runtime; `three` is the namespace object supplied by the app.
  */
final class SurfaceHost(three: js.Dynamic, val probe: LifecycleProbe)
    extends RendererHost[ThreeJsRuntime]:
  def create(canvas: dom.html.Canvas): ThreeJsRuntime =
    val rt = ThreeJsRuntime.create(three, canvas.asInstanceOf[js.Dynamic])
      .fold(e => throw IllegalStateException(e.toString), identity)
    probe.created += 1
    probe.lastContextState = rt.contextState.toString
    rt

  def resize(rt: ThreeJsRuntime, w: Int, h: Int): Unit =
    if w > 0 && h > 0 then
      rt.resize(ThreeCanvasSize.unsafe(w, h, dom.window.devicePixelRatio)).left.foreach(e =>
        probe.errors += e.toString
      )
      rt.draw().left.foreach(e => probe.errors += e.toString)
      probe.resizes += 1
      probe.lastContextState = rt.contextState.toString

  def dispose(rt: ThreeJsRuntime): Unit =
    rt.dispose().left.foreach(e => probe.errors += e.toString)
    probe.disposed += 1
