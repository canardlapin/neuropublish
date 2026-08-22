package neuropublish.viewer.laminar

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Lifecycle contract for a pane that hosts an imperative renderer (ScalaFIM volume controller or
  * Three.js surface backend). One state-carrying hook owns creation and idempotent disposal (ADR
  * 0003).
  */
trait RendererHost[R]:
  /** Create the renderer against a freshly mounted canvas. */
  def create(canvas: dom.html.Canvas): R

  /** Notify the renderer that its canvas box changed. */
  def resize(r: R, widthPx: Int, heightPx: Int): Unit

  /** Release listeners, GPU resources, and caches. Must be idempotent. */
  def dispose(r: R): Unit

object RendererHost:
  /** Tracks every live handle so tests can assert that unmount disposes exactly once. */
  final class Handle[R](val renderer: R, host: RendererHost[R]):
    private var disposed = false
    private var observer: Option[dom.ResizeObserver] = None
    def observe(el: dom.Element): Unit =
      val o = new dom.ResizeObserver((entries, _) =>
        entries.headOption.foreach { e =>
          host.resize(renderer, e.contentRect.width.toInt, e.contentRect.height.toInt)
        }
      )
      o.observe(el)
      observer = Some(o)
    def dispose(): Unit =
      if !disposed then
        disposed = true
        observer.foreach(_.disconnect())
        observer = None
        host.dispose(renderer)
    def isDisposed: Boolean = disposed

  /** A pane element whose canvas is created on mount and disposed exactly once on unmount. */
  def pane[R](
      host: RendererHost[R],
      onHandle: Handle[R] => Unit = (_: Handle[R]) => ()
  ): HtmlElement =
    val canvas = canvasTag(
      width := "100%",
      height := "100%",
      display := "block"
    )
    div(
      position := "relative",
      width := "100%",
      height := "100%",
      canvas,
      onMountUnmountCallbackWithState[HtmlElement, Handle[R]](
        mount = ctx =>
          val h = new Handle(host.create(canvas.ref), host)
          h.observe(ctx.thisNode.ref)
          onHandle(h)
          h
        ,
        unmount = (_, state) => state.foreach(_.dispose())
      )
    )
