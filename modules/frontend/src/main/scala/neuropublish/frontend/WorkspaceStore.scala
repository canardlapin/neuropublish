package neuropublish.frontend

import com.raquo.laminar.api.L.*
import intaglio.{DeviceContext, DisplayThreshold, DisplayWindow, ScalarColorizer}
import neuropublish.viewer.*
import neuropublish.viewer.laminar.*
import scalafim.image.*
import scalafim.image.view.*

/** Owns the pure `Workspace` state and pushes changes into the ScalaFIM host as typed actions.
  * Display changes never reread a rendition; a reorder or a colormap change rebuilds the
  * `ViewerModel` (from already-decoded volumes), carrying the viewer state across. The host's first
  * model is built from `initial`, so a URL-restored order/colormap renders from the first frame.
  */
final class WorkspaceStore(val loaded: Loaded, initial: Workspace):
  val state: Var[Workspace] = Var(initial)
  val readout: Var[Option[PanelReadout]] = Var(None)
  val probe = new LifecycleProbe
  private var live: Option[VolumeHost.Live] = None
  private var lastModelKey: Vector[(String, String)] = modelKey(initial)

  private val underlay = loaded.manifest.underlays.headOption.getOrElse(throw RuntimeException(
    "This revision has no underlay."
  ))
  private val underlayVol = loaded.volumes.getOrElse(
    underlay.asset,
    throw RuntimeException("The underlay rendition is not ready yet.")
  )
  val space: VolumeSpace = VolumeSpace(underlayVol.space)

  /** What requires a model rebuild: draw order and colormap per layer. */
  private def modelKey(w: Workspace) = w.layers.map(l => (l.id, l.current.colormap))

  /** Manifest/URL threshold modes onto Intaglio's thresholds (two-sided magnitude, one-sided
    * cutoffs).
    */
  private def thresholdOf(d: LayerDisplay): DisplayThreshold =
    val m = d.threshold.min
    d.threshold.mode match
      case "two-sided" if m > 0 =>
        DisplayThreshold.twoSidedMagnitude(m).getOrElse(DisplayThreshold.Disabled)
      case "positive" =>
        DisplayThreshold.below(m).getOrElse(DisplayThreshold.Disabled) // show v >= min
      case "negative" =>
        DisplayThreshold.above(-m).getOrElse(DisplayThreshold.Disabled) // show v <= -min
      case _ => DisplayThreshold.Disabled

  private def scalar(id: String, vol: NeuroVol[Double], d: LayerDisplay) =
    SliceLayer(
      LayerId.unsafe(id),
      vol,
      SliceSampling.Nearest(0.0),
      ScalarColorizer(
        DisplayWindow.unsafe(d.window.min, d.window.max),
        Colormaps.ramp(d.colormap),
        threshold = thresholdOf(d)
      ),
      LayerOpacity.unsafe(d.opacity)
    )

  /** Build the ScalaFIM model. Workspace lists top-first; ScalaFIM draws vector order bottom→top,
    * hence the reverse.
    */
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
    val overlays = w.layers.reverse.flatMap { l =>
      loaded.volumeFields.find(_.id == l.id).flatMap(f =>
        loaded.volumes.get(loaded.assetOf(f))
      ).map(v => scalar(l.id, v, l.current))
    }
    ViewerModel.unsafe(space, under +: overlays)

  val host: VolumeHost = new VolumeHost(
    model(initial),
    ViewerSession(ViewerState.centered(space), DeviceContext.unsafe(600.0, 400.0)),
    probe
  )

  def attach(l: VolumeHost.Live): Unit =
    live = Some(l)
    l.onCursor = Some(() => readout.set(host.readout(l)))
    applyAll(state.now())
    state.now().cursor match
      case Some((x, y, z)) => host.dispatch(l, ViewerAction.SetCursor(WorldPoint(x, y, z)))
      case None => readout.set(host.readout(l))

  private def applyAll(w: Workspace): Unit = live.foreach { l =>
    w.layers.foreach { layer =>
      val id = LayerId.unsafe(layer.id); val d = layer.current
      host.dispatch(l, ViewerAction.SetVisibility(id, d.visible))
      host.dispatch(l, ViewerAction.SetOpacity(id, LayerOpacity.unsafe(d.opacity)))
      host.dispatch(l, ViewerAction.SetWindow(id, DisplayWindow.unsafe(d.window.min, d.window.max)))
      host.dispatch(l, ViewerAction.SetThreshold(id, thresholdOf(d)))
    }
  }

  /** Like dispatch, but reports whether the reducer accepted the action (e.g. window min ≥ max is
    * rejected).
    */
  def tryDispatch(a: Workspace.Action): Boolean =
    val before = state.now()
    dispatch(a)
    state.now() != before

  /** Apply an action to the pure state, then mirror the delta into the renderer. */
  def dispatch(a: Workspace.Action): Unit =
    val after = Workspace.reduce(state.now(), a)
    state.set(after)
    live.foreach { l =>
      val key = modelKey(after)
      if key != lastModelKey then
        lastModelKey = key
        host.rebuild(
          l,
          model(after)
        ) // order and colormap live in the model; ScalaFIM has neither a reorder nor a colormap action
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

  /** A pick in the canvas: the controller reduces it synchronously; we mirror the resulting cursor
    * back.
    */
  def pick(cssX: Double, cssY: Double): Unit = live.foreach { l =>
    host.pick(l, cssX, cssY)
    host.state(l).foreach(s =>
      state.update(_.copy(cursor = Some((s.cursor.x, s.cursor.y, s.cursor.z))))
    )
  }
  def scroll(cssX: Double, cssY: Double, steps: Int): Unit =
    live.foreach(l => host.scroll(l, cssX, cssY, steps))
