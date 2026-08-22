package neuropublish.frontend

import com.raquo.laminar.api.L.*
import intaglio.{DeviceContext, DisplayThreshold, DisplayWindow, ScalarColorizer}
import neuropublish.viewer.*
import neuropublish.viewer.laminar.*
import org.scalajs.dom
import scalafim.image.*
import scalafim.image.view.*

/** Owns the pure `Workspace` state and pushes changes into the ScalaFIM host as typed actions.
  * Display changes never reread a rendition; only a reorder rebuilds the `ViewerModel` (from
  * already-decoded volumes).
  */
final class WorkspaceStore(val loaded: Loaded):
  val state: Var[Workspace] = Var(loaded.initialWorkspace)
  val readout: Var[Option[PanelReadout]] = Var(None)
  val probe = new LifecycleProbe
  private var live: Option[VolumeHost.Live] = None
  private var lastOrder: Vector[String] = state.now().layers.map(_.id)

  private val underlay = loaded.manifest.underlays.headOption.getOrElse(throw RuntimeException(
    "This revision has no underlay."
  ))
  private val underlayVol = loaded.volumes.getOrElse(
    underlay.asset,
    throw RuntimeException("The underlay rendition is not ready yet.")
  )
  val space: VolumeSpace = VolumeSpace(underlayVol.space)

  private def scalar(id: String, vol: NeuroVol[Double], d: LayerDisplay) =
    val thr =
      if d.threshold.mode == "two-sided" && d.threshold.min > 0
      then
        DisplayThreshold.transparentBand(
          -d.threshold.min,
          d.threshold.min
        ).getOrElse(DisplayThreshold.Disabled)
      else DisplayThreshold.Disabled
    SliceLayer(
      LayerId.unsafe(id),
      vol,
      SliceSampling.Nearest(0.0),
      ScalarColorizer(
        DisplayWindow.unsafe(d.window.min, d.window.max),
        Colormaps.ramp(d.colormap),
        threshold = thr
      ),
      LayerOpacity.unsafe(d.opacity)
    )

  /** Build the ScalaFIM model from the workspace's draw order. Layer draw order = vector order. */
  def model(w: Workspace): ViewerModel =
    val (ulo, uhi) =
      loaded.summaries.get(underlay.asset).map(s => (s.min, s.max)).getOrElse((0.0, 1.0))
    val under = SliceLayer(
      LayerId.unsafe(underlay.asset),
      underlayVol,
      SliceSampling.Nearest(0.0),
      ScalarColorizer(
        DisplayWindow.unsafe(ulo, math.max(uhi, ulo + 1e-9)),
        intaglio.ColorRamp.Grayscale
      )
    )
    val overlays = w.layers.reverse.flatMap {
      l => // last in list = drawn on top, so reverse for draw order bottom→top
        loaded.volumeFields.find(_.id == l.id).flatMap(f =>
          loaded.volumes.get(loaded.assetOf(f))
        ).map(v => scalar(l.id, v, l.current))
    }
    ViewerModel.unsafe(space, under +: overlays)

  val host: VolumeHost = new VolumeHost(
    model(state.now()),
    ViewerSession(ViewerState.centered(space), DeviceContext.unsafe(600.0, 400.0)),
    probe
  )

  def attach(l: VolumeHost.Live): Unit =
    live = Some(l)
    l.onFrame = Some(() => readout.set(host.readout(l)))
    applyAll(state.now())
    state.now().cursor.foreach((x, y, z) =>
      host.dispatch(l, ViewerAction.SetCursor(WorldPoint(x, y, z)))
    )

  private def applyAll(w: Workspace): Unit = live.foreach { l =>
    w.layers.foreach { layer =>
      val id = LayerId.unsafe(layer.id); val d = layer.current
      host.dispatch(l, ViewerAction.SetVisibility(id, d.visible))
      host.dispatch(l, ViewerAction.SetOpacity(id, LayerOpacity.unsafe(d.opacity)))
      host.dispatch(l, ViewerAction.SetWindow(id, DisplayWindow.unsafe(d.window.min, d.window.max)))
      host.dispatch(l, ViewerAction.SetThreshold(id, thresholdOf(d)))
    }
  }

  private def thresholdOf(d: LayerDisplay): DisplayThreshold =
    if d.threshold.mode == "two-sided" && d.threshold.min > 0
    then
      DisplayThreshold.transparentBand(
        -d.threshold.min,
        d.threshold.min
      ).getOrElse(DisplayThreshold.Disabled)
    else DisplayThreshold.Disabled

  /** Apply an action to the pure state, then mirror the delta into the renderer. */
  def dispatch(a: Workspace.Action): Unit =
    val before = state.now()
    val after = Workspace.reduce(before, a)
    state.set(after)
    live.foreach { l =>
      val order = after.layers.map(_.id)
      val colormapChanged = after.layers.exists(x =>
        before.layers.find(_.id == x.id).exists(_.current.colormap != x.current.colormap)
      )
      if order != lastOrder || colormapChanged then
        lastOrder = order
        host.rebuild(
          l,
          model(after)
        ) // colormap lives in the colorizer; ScalaFIM has no SetColormap action
        applyAll(after)
      else
        a match
          case Workspace.Action.SetVisible(id, v) =>
            host.dispatch(l, ViewerAction.SetVisibility(LayerId.unsafe(id), v))
          case Workspace.Action.SetOpacity(id, _) =>
            after.layers.find(_.id == id).foreach(x =>
              host.dispatch(
                l,
                ViewerAction.SetOpacity(LayerId.unsafe(id), LayerOpacity.unsafe(x.current.opacity))
              )
            )
          case Workspace.Action.SetWindow(id, _) =>
            after.layers.find(_.id == id).foreach(x =>
              host.dispatch(
                l,
                ViewerAction.SetWindow(
                  LayerId.unsafe(id),
                  DisplayWindow.unsafe(x.current.window.min, x.current.window.max)
                )
              )
            )
          case Workspace.Action.SetThreshold(id, _) =>
            after.layers.find(_.id == id).foreach(x =>
              host.dispatch(
                l,
                ViewerAction.SetThreshold(LayerId.unsafe(id), thresholdOf(x.current))
              )
            )
          case Workspace.Action.ResetLayer(_) | Workspace.Action.ResetAll => applyAll(after)
          case Workspace.Action.SetCursor(x, y, z) =>
            host.dispatch(l, ViewerAction.SetCursor(WorldPoint(x, y, z)))
          case _ => ()
    }

  /** A pick in the canvas: ScalaFIM resolves it to a world cursor; we mirror the cursor back. */
  def pick(cssX: Double, cssY: Double): Unit = live.foreach { l =>
    host.pick(l, cssX, cssY)
    host.state(l).foreach(s =>
      state.update(_.copy(cursor = Some((s.cursor.x, s.cursor.y, s.cursor.z))))
    )
  }
  def scroll(cssX: Double, cssY: Double, steps: Int): Unit =
    live.foreach(l => host.scroll(l, cssX, cssY, steps))
