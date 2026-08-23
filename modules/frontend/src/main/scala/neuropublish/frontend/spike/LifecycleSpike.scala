package neuropublish.frontend.spike

import com.raquo.laminar.api.L.*
import intaglio.{ColorRamp, DeviceContext, DisplayWindow, ScalarColorizer}
import neuropublish.viewer.laminar.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scalafim.image.*
import scalafim.image.view.*
import scalafim.surface.{Hemisphere, SurfaceGeometry, SurfaceKind, TriangleMesh}
import scalafim.surface.view.*

/** Stage 0 Spike B harness, extended in Stage 5. Exported to the page; driven by Playwright. Mounts
  * a Laminar pane hosting a real ScalaFIM renderer into a container, unmounts it through Laminar,
  * and reports lifecycle counters. The surface pane mounts a real two-hemisphere
  * `SurfaceViewerModel` (bilateral layout, one scalar layer per hemisphere) through `SurfaceHost`.
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
      PrimitiveBuffers.tabulate[Double](space.nVoxels)(i => (i % 97).toDouble),
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

  val LeftId: SurfaceId = SurfaceId.unsafe("lh")
  val RightId: SurfaceId = SurfaceId.unsafe("rh")

  /** Two tetrahedra, one per hemisphere, placed at ±20 mm in x by their surface-to-world. */
  private def hemisphere(h: Hemisphere, offsetX: Double): SurfaceGeometry =
    SurfaceGeometry(
      TriangleMesh.fromRows(
        Vector(
          Vector(0.0, 0.0, 0.0),
          Vector(10.0, 0.0, 0.0),
          Vector(0.0, 10.0, 0.0),
          Vector(0.0, 0.0, 10.0)
        ),
        Vector((0, 1, 2), (0, 1, 3), (0, 2, 3), (1, 2, 3))
      ),
      h,
      SurfaceKind.Pial,
      DMat.fromRows(Vector(
        Vector(1.0, 0.0, 0.0, offsetX),
        Vector(0.0, 1.0, 0.0, 0.0),
        Vector(0.0, 0.0, 1.0, 0.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      ))
    )

  lazy val surfaceModel: SurfaceViewerModel =
    val left = hemisphere(Hemisphere.Left, -20.0)
    val right = hemisphere(Hemisphere.Right, 20.0)
    val colorizer = ScalarColorizer(DisplayWindow.unsafe(-2.5, 2.5), ColorRamp.Heat)
    (for
      l <- SurfaceLayer.scalar(
        SurfaceLayerId.unsafe("t@left"),
        LeftId,
        left,
        Array(-2.0, -0.5, 0.5, 2.5),
        colorizer
      )
      r <- SurfaceLayer.scalar(
        SurfaceLayerId.unsafe("t@right"),
        RightId,
        right,
        Array(2.5, 0.5, -0.5, -2.0),
        colorizer
      )
      la <- SurfaceAsset.make(LeftId, left)
      ra <- SurfaceAsset.make(RightId, right)
      m <- SurfaceViewerModel.make(Vector(la, ra), Vector(l, r))
    yield m).fold(e => throw IllegalStateException(e.toString), identity)

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
    val host = new SurfaceHost(three, Some(surfaceModel), surfaceProbe)
    mount(
      containerId,
      RendererHost.pane(
        host,
        h => {
          handles += containerId -> h
          surfaces += containerId -> (host, h.renderer)
          host.dispatch(
            h.renderer,
            SurfaceViewerAction.SetLayout(SurfaceLayout.Bilateral(LeftId, RightId))
          )
          host.dispatch(h.renderer, SurfaceViewerAction.SetViewpoint(SurfaceViewpoint.Dorsal))
        }
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

  /** Link the mounted surface pane's cursor to a world point; reports the explicit link result. */
  @JSExportTopLevel("spikeSurfaceLink")
  def surfaceLink(containerId: String, x: Double, y: Double, z: Double): js.Dynamic =
    surfaces.get(containerId).map { (h, live) =>
      h.setCursor(live, WorldPoint(x, y, z)) match
        case SurfaceHost.Link.Linked(s, v, d) =>
          js.Dynamic.literal(kind = "linked", surface = s.value, vertex = v, distance = d)
        case SurfaceHost.Link.OutOfRange(d) =>
          js.Dynamic.literal(kind = "out-of-range", distance = d)
        case SurfaceHost.Link.NoGeometry => js.Dynamic.literal(kind = "no-geometry")
    }.getOrElse(js.Dynamic.literal(kind = "unmounted"))

  /** Pick at CSS pixel coordinates of the mounted surface pane. */
  @JSExportTopLevel("spikeSurfacePick")
  def surfacePick(containerId: String, x: Double, y: Double): js.Dynamic =
    surfaces.get(containerId).flatMap((h, live) => h.pick(live, x, y)).map(p =>
      js.Dynamic.literal(
        surface = p.surface.value,
        vertex = p.vertex,
        x = p.world.x,
        y = p.world.y,
        z = p.world.z
      )
    ).getOrElse(null)

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
