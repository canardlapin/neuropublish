package neuropublish.frontend

import com.raquo.laminar.api.L.*
import intaglio.{DeviceContext, DisplayOpacity, DisplayThreshold, DisplayWindow, ScalarColorizer}
import neuropublish.viewer.*
import neuropublish.viewer.laminar.*
import scala.scalajs.js
import scalafim.image.*
import scalafim.image.view.*
import scalafim.surface.CorticalHemisphere
import scalafim.surface.view.{
  BilateralOrder,
  CameraProjection,
  FieldOfViewDegrees,
  OrthographicScale,
  SurfaceAsset,
  SurfaceId,
  SurfaceLayer,
  SurfaceLayerId,
  SurfaceLayout,
  SurfaceReadout,
  SurfaceViewerAction,
  SurfaceViewerModel,
  SurfaceViewpoint
}

/** Owns the pure `Workspace` state and pushes changes into the ScalaFIM hosts as typed actions.
  * Display changes never reread a rendition; a reorder or a colormap change rebuilds the viewer
  * models (from already-decoded volumes and vertex fields), carrying the viewer state across. The
  * hosts' first models are built from `initial`, so a URL-restored order/colormap renders from the
  * first frame.
  *
  * One result field is one layer; it renders in the volume pane when it has a volume representation
  * and on each hemisphere it has a surface representation for. The world cursor is shared: a pick
  * in either pane sets it, the volume pane shows the voxel under it, and the surface pane links it
  * to the nearest vertex within `SurfaceHost.DefaultLinkRadius` or says why not.
  */
final class WorkspaceStore(
    val loaded: Loaded,
    initial: Workspace,
    three: js.Dynamic = Three.namespace
):
  val state: Var[Workspace] = Var(initial)
  val readout: Var[Option[PanelReadout]] = Var(None)
  val surfaceReadout: Var[Option[SurfaceReadout]] = Var(None)
  val link: Var[Option[SurfaceHost.Link]] = Var(None)

  /** Which pane set the cursor last ("volume" | "surface"); the other pane shows the link state. */
  val cursorSource: Var[Option[String]] = Var(None)
  val probe = new LifecycleProbe
  val surfaceProbe = new LifecycleProbe
  private var live: Option[VolumeHost.Live] = None
  private var surfaceLive: Option[SurfaceHost.Live] = None
  private var lastModelKey: Vector[(String, String)] = modelKey(initial)
  private var lastSurfaceKey: Vector[(String, String)] = modelKey(initial)

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

  private def colorizer(d: LayerDisplay) = ScalarColorizer(
    DisplayWindow.unsafe(d.window.min, d.window.max),
    Colormaps.ramp(d.colormap),
    threshold = thresholdOf(d)
  )

  private def scalar(id: String, vol: NeuroVol[Double], d: LayerDisplay) =
    SliceLayer(
      LayerId.unsafe(id),
      vol,
      SliceSampling.Nearest(0.0),
      colorizer(d),
      LayerOpacity.unsafe(d.opacity)
    )

  // ---------------------------------------------------------------- volume

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
      loaded.field(l.id).flatMap(loaded.volumeAssetOf).flatMap(loaded.volumes.get)
        .map(v => scalar(l.id, v, l.current))
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
    val w = state.now()
    // the pane may be remounted after a preset switch: the host's first model is `initial`'s
    if modelKey(w) != lastModelKey || modelKey(w) != modelKey(initial) then
      host.rebuild(l, model(w))
    lastModelKey = modelKey(w)
    applyAll(w)
    w.cursor match
      case Some((x, y, z)) => host.dispatch(l, ViewerAction.SetCursor(WorldPoint(x, y, z)))
      case None => readout.set(host.readout(l))

  def detach(): Unit =
    live = None
    readout.set(None)

  private def applyAll(w: Workspace): Unit = live.foreach { l =>
    w.layers.filter(_.representations.volume).foreach { layer =>
      val id = LayerId.unsafe(layer.id); val d = layer.current
      host.dispatch(l, ViewerAction.SetVisibility(id, d.visible))
      host.dispatch(l, ViewerAction.SetOpacity(id, LayerOpacity.unsafe(d.opacity)))
      host.dispatch(l, ViewerAction.SetWindow(id, DisplayWindow.unsafe(d.window.min, d.window.max)))
      host.dispatch(l, ViewerAction.SetThreshold(id, thresholdOf(d)))
    }
  }

  // ---------------------------------------------------------------- surface

  private val leftSurface = loaded.surface("left")
  private val rightSurface = loaded.surface("right")
  private val surfaceAssets: Vector[SurfaceAsset] =
    (leftSurface.toVector ++ rightSurface.toVector).flatMap((d, g) =>
      SurfaceAsset.make(SurfaceId.unsafe(d.id), g).toOption
    )

  /** Whether the surface pane has geometry it can place (a left and/or right hemisphere). */
  val hasSurfaces: Boolean = surfaceAssets.nonEmpty

  /** Declared surfaces that decoded but cannot be placed: neither left nor right. */
  val unplaceableSurfaces: List[SurfaceDecl] =
    loaded.surfaces.values.map(_._1).filterNot(d =>
      d.hemisphere == "left" || d.hemisphere == "right"
    )
      .toList.sortBy(_.id)

  /** Bilateral when both hemispheres decoded, single otherwise. */
  val surfaceLayout: Option[SurfaceLayout] = (leftSurface, rightSurface) match
    case (Some((l, _)), Some((r, _))) =>
      Some(SurfaceLayout.Bilateral(
        SurfaceId.unsafe(l.id),
        SurfaceId.unsafe(r.id),
        BilateralOrder.LeftThenRight
      ))
    case (Some((l, _)), None) => Some(SurfaceLayout.Single(SurfaceId.unsafe(l.id)))
    case (None, Some((r, _))) => Some(SurfaceLayout.Single(SurfaceId.unsafe(r.id)))
    case _ => None

  /** Surface layer id for one field on one hemisphere. */
  def surfaceLayerId(field: String, hemisphere: String): SurfaceLayerId =
    SurfaceLayerId.unsafe(s"$field@$hemisphere")

  /** The surface model: every decoded hemisphere, and one scalar layer per (field, hemisphere) with
    * a surface representation. `None` when the revision has no surface geometry at all.
    */
  def surfaceModel(w: Workspace): Option[SurfaceViewerModel] =
    if surfaceAssets.isEmpty then None
    else
      val layers = w.layers.reverse.flatMap { l =>
        loaded.field(l.id).toVector.flatMap(f =>
          loaded.surfaceRepsOf(f).toVector.flatMap { rep =>
            for
              field <- loaded.vertexFields.get(rep.asset)
              (_, geometry) <- loaded.surfaces.get(rep.surface)
              if surfaceAssets.exists(_.id == SurfaceId.unsafe(rep.surface))
              layer <- SurfaceLayer.scalar(
                surfaceLayerId(l.id, rep.hemisphere),
                SurfaceId.unsafe(rep.surface),
                geometry,
                field.data,
                colorizer(l.current),
                opacity = DisplayOpacity.unsafe(l.current.opacity)
              ).toOption
            yield layer
          }
        )
      }
      SurfaceViewerModel.make(surfaceAssets, layers).toOption

  val surfaceHost: SurfaceHost = new SurfaceHost(three, surfaceModel(initial), surfaceProbe)

  private def viewpointOf(c: SurfaceCameraState): SurfaceViewpoint = c.viewpoint match
    case "right" => SurfaceViewpoint.Lateral(CorticalHemisphere.Right)
    case "dorsal" => SurfaceViewpoint.Dorsal
    case "ventral" => SurfaceViewpoint.Ventral
    case "anterior" => SurfaceViewpoint.Anterior
    case "posterior" => SurfaceViewpoint.Posterior
    case _ => SurfaceViewpoint.Lateral(CorticalHemisphere.Left)

  private def projectionOf(c: SurfaceCameraState): CameraProjection = c.projection match
    case "orthographic" => CameraProjection.Orthographic(OrthographicScale.unsafe(1.0))
    case _ => CameraProjection.Perspective(FieldOfViewDegrees.Default)

  def attachSurface(l: SurfaceHost.Live): Unit =
    surfaceLive = Some(l)
    l.onFrame = Some(() => surfaceReadout.set(surfaceHost.readout(l)))
    val w = state.now()
    if modelKey(w) != lastSurfaceKey || modelKey(w) != modelKey(initial) then
      surfaceHost.rebuild(l, surfaceModel(w))
    lastSurfaceKey = modelKey(w)
    applySurfaceAll(w)
    w.cursor.foreach((x, y, z) => linkCursor(WorldPoint(x, y, z)))

  def detachSurface(): Unit =
    surfaceLive = None
    surfaceReadout.set(None)
    link.set(None)

  private def applySurfaceAll(w: Workspace): Unit = surfaceLive.foreach { l =>
    surfaceLayout.foreach(lay => surfaceHost.dispatch(l, SurfaceViewerAction.SetLayout(lay)))
    applySurfaceCamera(w)
    w.layers.foreach { layer =>
      layer.representations.surfaces.foreach { h =>
        val id = surfaceLayerId(layer.id, h); val d = layer.current
        surfaceHost.dispatch(l, SurfaceViewerAction.SetLayerVisible(id, d.visible))
        surfaceHost.dispatch(
          l,
          SurfaceViewerAction.SetLayerOpacity(id, DisplayOpacity.unsafe(d.opacity))
        )
        surfaceHost.dispatch(
          l,
          SurfaceViewerAction.SetLayerWindow(id, DisplayWindow.unsafe(d.window.min, d.window.max))
        )
        surfaceHost.dispatch(l, SurfaceViewerAction.SetLayerThreshold(id, thresholdOf(d)))
      }
    }
  }

  private def applySurfaceCamera(w: Workspace): Unit = surfaceLive.foreach { l =>
    surfaceHost.dispatch(l, SurfaceViewerAction.SetViewpoint(viewpointOf(w.surfaceCamera)))
    surfaceHost.dispatch(l, SurfaceViewerAction.SetProjection(projectionOf(w.surfaceCamera)))
  }

  /** Link the world cursor into the surface pane and publish the explicit result. */
  private def linkCursor(world: WorldPoint): Unit =
    surfaceLive match
      case Some(l) => link.set(Some(surfaceHost.setCursor(l, world)))
      case None => link.set(None)

  // ---------------------------------------------------------------- dispatch

  /** Replace the whole state (a saved view re-applied, "Return to saved view"): rebuild the models
    * so order and colormaps follow, then mirror every display value and the cursor.
    */
  def replace(w: Workspace): Unit =
    state.set(w)
    lastModelKey = modelKey(w)
    lastSurfaceKey = modelKey(w)
    live.foreach { l =>
      host.rebuild(l, model(w))
      applyAll(w)
      w.cursor.foreach((x, y, z) => host.dispatch(l, ViewerAction.SetCursor(WorldPoint(x, y, z))))
    }
    surfaceLive.foreach { l =>
      surfaceHost.rebuild(l, surfaceModel(w))
      applySurfaceAll(w)
    }
    w.cursor.foreach((x, y, z) => linkCursor(WorldPoint(x, y, z)))

  /** Like dispatch, but reports whether the reducer accepted the action (e.g. window min ≥ max is
    * rejected).
    */
  def tryDispatch(a: Workspace.Action): Boolean =
    val before = state.now()
    dispatch(a)
    state.now() != before

  /** Apply an action to the pure state, then mirror the delta into both renderers. */
  def dispatch(a: Workspace.Action): Unit =
    val after = Workspace.reduce(state.now(), a)
    state.set(after)
    mirrorVolume(a, after)
    mirrorSurface(a, after)
    a match
      case Workspace.Action.SetCursor(x, y, z) => linkCursor(WorldPoint(x, y, z))
      case _ => ()

  private def mirrorVolume(a: Workspace.Action, after: Workspace): Unit = live.foreach { l =>
    val key = modelKey(after)
    if key != lastModelKey then
      lastModelKey = key
      // order and colormap live in the model; ScalaFIM has neither a reorder nor a colormap action
      host.rebuild(l, model(after))
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
            host.dispatch(l, ViewerAction.SetThreshold(LayerId.unsafe(id), thresholdOf(x.current)))
          )
        case Workspace.Action.ResetLayer(_) | Workspace.Action.ResetAll => applyAll(after)
        case Workspace.Action.SetCursor(x, y, z) =>
          host.dispatch(l, ViewerAction.SetCursor(WorldPoint(x, y, z)))
        case _ => ()
  }

  private def mirrorSurface(a: Workspace.Action, after: Workspace): Unit =
    surfaceLive.foreach { l =>
      val key = modelKey(after)
      if key != lastSurfaceKey then
        lastSurfaceKey = key
        surfaceHost.rebuild(l, surfaceModel(after))
        applySurfaceAll(after)
      else
        def each(id: String)(f: (SurfaceLayerId, LayerDisplay) => SurfaceViewerAction): Unit =
          after.layers.find(_.id == id).foreach(x =>
            x.representations.surfaces.foreach(h =>
              surfaceHost.dispatch(l, f(surfaceLayerId(id, h), x.current))
            )
          )
        a match
          case Workspace.Action.SetVisible(id, _) =>
            each(id)((sid, d) => SurfaceViewerAction.SetLayerVisible(sid, d.visible))
          case Workspace.Action.SetOpacity(id, _) =>
            each(id)((sid, d) =>
              SurfaceViewerAction.SetLayerOpacity(sid, DisplayOpacity.unsafe(d.opacity))
            )
          case Workspace.Action.SetWindow(id, _) =>
            each(id)((sid, d) =>
              SurfaceViewerAction.SetLayerWindow(
                sid,
                DisplayWindow.unsafe(d.window.min, d.window.max)
              )
            )
          case Workspace.Action.SetThreshold(id, _) =>
            each(id)((sid, d) => SurfaceViewerAction.SetLayerThreshold(sid, thresholdOf(d)))
          case Workspace.Action.ResetLayer(_) | Workspace.Action.ResetAll => applySurfaceAll(after)
          case Workspace.Action.SetSurfaceCamera(_) => applySurfaceCamera(after)
          case _ => ()
    }

  // ---------------------------------------------------------------- picks

  /** A pick in the volume canvas: the controller reduces it synchronously; we mirror the resulting
    * cursor back and link it into the surface pane.
    */
  def pick(cssX: Double, cssY: Double): Unit = live.foreach { l =>
    host.pick(l, cssX, cssY)
    cursorSource.set(Some("volume"))
    host.state(l).foreach { s =>
      state.update(_.copy(cursor = Some((s.cursor.x, s.cursor.y, s.cursor.z))))
      linkCursor(s.cursor)
    }
  }

  def scroll(cssX: Double, cssY: Double, steps: Int): Unit =
    live.foreach(l => host.scroll(l, cssX, cssY, steps))

  /** A pick on the surface pane: the picked vertex's world position becomes the shared cursor, so
    * the volume pane moves there and the surface pane links back to the same vertex at 0 mm.
    */
  def pickSurface(cssX: Double, cssY: Double): Boolean =
    surfaceLive.flatMap(l => surfaceHost.pick(l, cssX, cssY)) match
      case Some(p) =>
        cursorSource.set(Some("surface"))
        dispatch(Workspace.Action.SetCursor(p.world.x, p.world.y, p.world.z))
        true
      case None => false
