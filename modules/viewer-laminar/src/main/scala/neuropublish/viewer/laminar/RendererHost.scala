package neuropublish.viewer.laminar

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js

/** Lifecycle contract for a pane that hosts an imperative renderer (ScalaFIM volume controller or
  * Three.js surface backend). One state-carrying hook owns creation and idempotent disposal (ADR
  * 0003).
  */
trait RendererHost[R]:
  /** Create the renderer against a freshly mounted canvas. */
  def create(canvas: dom.html.Canvas): R

  /** Notify the renderer that its canvas box changed. */
  def resize(r: R, widthPx: Int, heightPx: Int): Unit

  /** Release listeners, GPU resources, and caches. Implementations must be idempotent. */
  def dispose(r: R): Unit

object RendererHost:
  /** Tracks every live handle so tests can assert that unmount disposes exactly once. */
  final class Handle[R](val renderer: R, host: RendererHost[R]):
    private var disposed = false
    private var observer: Option[dom.ResizeObserver] = None
    private var dprQuery: Option[(dom.MediaQueryList, js.Function1[dom.Event, Unit])] = None

    /** Resize on box changes and on device-pixel-ratio changes (a window dragged between screens
      * keeps its CSS box, so only a `(resolution: Ndppx)` media query notices; the query is
      * re-armed at the new ratio after each change).
      */
    def observe(el: dom.Element): Unit = if !disposed then
      val o = new dom.ResizeObserver((entries, _) =>
        entries.headOption.foreach { e =>
          host.resize(renderer, e.contentRect.width.toInt, e.contentRect.height.toInt)
        }
      )
      o.observe(el)
      observer = Some(o)
      watchDpr(el)

    private def watchDpr(el: dom.Element): Unit =
      unwatchDpr()
      val q = dom.window.matchMedia(s"(resolution: ${dom.window.devicePixelRatio}dppx)")
      val onChange: js.Function1[dom.Event, Unit] = _ =>
        if !disposed then
          val r = el.getBoundingClientRect()
          host.resize(renderer, r.width.toInt, r.height.toInt)
          watchDpr(el)
      q.addEventListener("change", onChange)
      dprQuery = Some((q, onChange))

    private def unwatchDpr(): Unit =
      dprQuery.foreach((q, f) => q.removeEventListener("change", f))
      dprQuery = None

    def dispose(): Unit =
      if !disposed then
        disposed = true
        observer.foreach(_.disconnect())
        observer = None
        unwatchDpr()
        host.dispose(renderer)
    def isDisposed: Boolean = disposed

  /** A pane element whose canvas is created on mount and disposed exactly once on unmount. The
    * canvas itself is created per mount: a WebGL canvas whose context was deliberately lost on
    * dispose cannot be given a new context, so a remounted pane (a preset switch brings a pane
    * back) must start from a fresh element.
    */
  def pane[R](
      host: RendererHost[R],
      onHandle: Handle[R] => Unit = (_: Handle[R]) => (),
      onDispose: Handle[R] => Unit = (_: Handle[R]) => ()
  ): HtmlElement =
    div(
      position := "relative",
      width := "100%",
      height := "100%",
      onMountUnmountCallbackWithState[HtmlElement, (Handle[R], dom.html.Canvas)](
        mount = ctx =>
          val canvas = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
          canvas.style.width = "100%"
          canvas.style.height = "100%"
          canvas.style.display = "block"
          ctx.thisNode.ref.appendChild(canvas)
          val h = new Handle(host.create(canvas), host)
          h.observe(ctx.thisNode.ref)
          onHandle(h)
          (h, canvas)
        ,
        unmount = (node, state) =>
          state.foreach { (h, canvas) =>
            h.dispose()
            onDispose(h)
            if canvas.parentNode == node.ref then node.ref.removeChild(canvas)
          }
      )
    )
