package neuropublish.frontend.spike

import com.raquo.laminar.api.L.*
import intaglio.{DeviceContext, DisplayWindow, ScalarColorizer}
import neuropublish.viewer.laminar.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scalafim.image.*
import scalafim.image.view.*

/** Stage 0 Spike B harness. Exported to the page; driven by Playwright. Mounts a Laminar pane
  * hosting a real ScalaFIM renderer into a container, unmounts it through Laminar, and reports
  * lifecycle counters.
  */
object LifecycleSpike:
  private val volumeProbe = new LifecycleProbe
  private val surfaceProbe = new LifecycleProbe
  private var roots = Map.empty[String, RootNode]
  private var handles = Map.empty[String, RendererHost.Handle[?]]
  private var surfaces = Map.empty[String, (SurfaceHost, SurfaceHost.Live)]
  private var disposedHandles = 0

  private lazy val model: ViewerModel =
    val space = VolumeSpace(NeuroSpace(Vector(16, 16, 16)))
    val vol = NeuroVol.fromLinearChecked(
      NArrayUtil.tabulate[Double](space.nVoxels)(i => (i % 97).toDouble),
      space.toNeuroSpace
    ).fold(e => throw IllegalStateException(e.toString), identity)
    ViewerModel.unsafe(
      space,
      Vector(SliceLayer(
        LayerId.unsafe("v"),
        vol,
        SliceSampling.Nearest(0.0),
        ScalarColorizer(DisplayWindow.unsafe(0.0, 96.0))
      ))
    )

  @JSExportTopLevel("spikeMountVolume")
  def mountVolume(containerId: String): Unit =
    val host = new VolumeHost(
      model,
      ViewerSession(ViewerState.centered(model.referenceSpace), DeviceContext.unsafe(300.0, 200.0)),
      volumeProbe
    )
    mount(containerId, RendererHost.pane(host, h => handles += containerId -> h))

  @JSExportTopLevel("spikeMountSurface")
  def mountSurface(containerId: String, three: js.Dynamic): Unit =
    val host = new SurfaceHost(three, surfaceProbe)
    mount(
      containerId,
      RendererHost.pane(
        host,
        h => { handles += containerId -> h; surfaces += containerId -> (host, h.renderer) }
      )
    )

  private def mount(containerId: String, pane: HtmlElement): Unit =
    val container = dom.document.getElementById(containerId)
    roots += containerId -> render(container, pane)

  @JSExportTopLevel("spikeUnmount")
  def unmount(containerId: String): Unit =
    roots.get(containerId).foreach(_.unmount())
    roots -= containerId
    handles.get(containerId).foreach(h => if h.isDisposed then disposedHandles += 1)
    handles -= containerId
    surfaces -= containerId

  /** Context state of a still-mounted surface pane (the leak sentinel). */
  @JSExportTopLevel("spikeSurfaceContextState")
  def surfaceContextState(containerId: String): String =
    surfaces.get(containerId).map((h, live) => h.contextState(live)).getOrElse("unmounted")

  @JSExportTopLevel("spikeReport")
  def report(): js.Dynamic =
    def p(x: LifecycleProbe) = js.Dynamic.literal(
      created = x.created,
      disposed = x.disposed,
      resizes = x.resizes,
      frames = x.frames,
      rafOutstanding = x.rafOutstanding,
      lastContextState = x.lastContextState,
      contextLost = x.contextLost,
      errors = js.Array(x.errors.toSeq*)
    )
    js.Dynamic.literal(
      volume = p(volumeProbe),
      surface = p(surfaceProbe),
      disposedHandles = disposedHandles,
      liveHandles = handles.size
    )
