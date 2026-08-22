package neuropublish.frontend

import com.raquo.laminar.api.L.*
import intaglio.{ColorRamp, DeviceContext, DisplayThreshold, DisplayWindow, Rgba32, ScalarColorizer}
import neuropublish.api.*
import neuropublish.protocol.json.Manifest
import neuropublish.rendition.VolumeRendition
import neuropublish.viewer.laminar.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scalafim.image.*
import scalafim.image.view.*

/** Stage 1: the latest revision of one project, rendered as underlay + overlays through the
  * ScalaFIM volume host. Plain DOM; layer visibility and opacity only (reordering needs a model
  * rebuild and lands with the Stage 3 controls). No visual system yet (plan: design cadence).
  */
object RevisionPage:
  final case class Loaded(
      detail: RevisionDetail,
      manifest: Manifest,
      volumes: Map[String, NeuroVol[Double]]
  )

  def load(api: Api, ws: String, project: String, revisionId: Option[String]): Future[Loaded] =
    for
      id <- revisionId.fold(api.project(ws, project).map(
        _.head.getOrElse(throw RuntimeException("project has no revisions"))
      ))(Future.successful)
      detail <- api.revision(id)
      manifest <- Future.fromTry(detail.manifest.as[Manifest].toTry)
      ready = detail.renditions.filter(_.status == "ready")
      vols <- Future.traverse(ready) { r =>
        api.rendition(r).map { (hdr, bytes) =>
          val v = VolumeRendition.decodeHeader(hdr).flatMap(VolumeRendition.decode(_, bytes)).fold(
            m => throw RuntimeException(s"${r.assetId}: $m"),
            identity
          )
          r.assetId -> v
        }
      }
    yield Loaded(detail, manifest, vols.toMap)

  private val cold = Rgba32.unsafe(0x1f, 0x4e, 0x9c, 0xff)
  private val hot = Rgba32.unsafe(0xff, 0xe0, 0x66, 0xff)

  def render(loaded: Loaded): HtmlElement =
    val m = loaded.manifest
    val underlay = m.underlays.headOption
    val overlayFields = m.resultFields.filter(
      _.representations.exists(_.kind == "volume")
    ).sortBy(_.order.getOrElse(Int.MaxValue))
    val underlayVol = underlay.flatMap(u => loaded.volumes.get(u.asset)).getOrElse(
      throw RuntimeException("no underlay rendition")
    )
    val space = VolumeSpace(underlayVol.space)

    /** Published recommendation (plan: "published recommendation versus current view"); Stage 1 has
      * no overrides yet.
      */
    def published(f: neuropublish.protocol.json.ResultField): (Double, Double, DisplayThreshold) =
      val c = f.publishedDisplay.map(_.hcursor)
      val lo = c.flatMap(_.downField("window").downField("min").as[Double].toOption).getOrElse(-8.0)
      val hi = c.flatMap(_.downField("window").downField("max").as[Double].toOption).getOrElse(8.0)
      val thr = c.flatMap(_.downField("threshold").downField("min").as[Double].toOption)
        .flatMap(m => DisplayThreshold.transparentBand(-m, m).toOption).getOrElse(
          DisplayThreshold.Disabled
        )
      (lo, hi, thr)

    def dataRange(vol: NeuroVol[Double]): (Double, Double) =
      var lo = Double.PositiveInfinity; var hi = Double.NegativeInfinity
      val n = vol.space.spatialDims.product; var i = 0
      while i < n do { val x = vol.linear(i); if x < lo then lo = x; if x > hi then hi = x; i += 1 }
      if lo < hi then (lo, hi) else (0.0, 1.0)

    def scalarLayer(
        id: String,
        vol: NeuroVol[Double],
        lo: Double,
        hi: Double,
        ramp: ColorRamp,
        threshold: DisplayThreshold = DisplayThreshold.Disabled
    ) =
      SliceLayer(
        LayerId.unsafe(id),
        vol,
        SliceSampling.Nearest(0.0),
        ScalarColorizer(DisplayWindow.unsafe(lo, hi), ramp, threshold = threshold)
      )

    val (ulo, uhi) = dataRange(underlayVol)
    val underlayLayer = scalarLayer(underlay.get.asset, underlayVol, ulo, uhi, ColorRamp.Grayscale)
    val overlayLayers = overlayFields.flatMap(f =>
      f.representations.find(_.kind == "volume").flatMap(r => loaded.volumes.get(r.asset)).map(
        v => {
          val (lo, hi, thr) = published(f);
          f -> scalarLayer(f.id, v, lo, hi, ColorRamp(cold, hot), thr)
        }
      )
    )
    val model = ViewerModel.unsafe(space, (underlayLayer +: overlayLayers.map(_._2)).toVector)

    // published recommendation: t and z visible by default, effect / se hidden
    val initiallyVisible = overlayFields.filter(f =>
      f.measure.endsWith("/t-statistic") || f.measure.endsWith("/z-statistic")
    ).map(_.id).toSet
    val visibility = Var(overlayFields.map(f => f.id -> initiallyVisible.contains(f.id)).toMap)
    val opacity = Var(overlayFields.map(f => f.id -> 0.85).toMap)
    val probe = new LifecycleProbe
    val host = new VolumeHost(
      model,
      ViewerSession(ViewerState.centered(space), DeviceContext.unsafe(600.0, 400.0)),
      probe
    )
    var handle: Option[RendererHost.Handle[VolumeHost.Live]] = None

    def dispatch(a: ViewerAction): Unit = handle.foreach { h =>
      h.renderer.controller.dispatch(a).left.foreach(e => dom.console.error(e.toString))
      host.requestRender(h.renderer)
    }
    def syncAll(): Unit =
      visibility.now().foreach((id, v) =>
        dispatch(ViewerAction.SetVisibility(LayerId.unsafe(id), v))
      )
      opacity.now().foreach((id, o) =>
        dispatch(ViewerAction.SetOpacity(LayerId.unsafe(id), LayerOpacity.unsafe(o)))
      )

    def layerRow(f: neuropublish.protocol.json.ResultField) =
      div(
        cls := "layer",
        dataAttr("layer") := f.id,
        input(
          typ := "checkbox",
          controlled(
            checked <-- visibility.signal.map(_(f.id)),
            onClick.mapToChecked --> { v =>
              visibility.update(_ + (f.id -> v));
              dispatch(ViewerAction.SetVisibility(LayerId.unsafe(f.id), v))
            }
          )
        ),
        span(
          cls := "layer-name",
          s"${m.resultFields.find(_.id == f.id).map(_.estimand).getOrElse("")} · ${f.measure.split('/').last}"
        ),
        input(
          typ := "range",
          minAttr := "0",
          maxAttr := "100",
          stepAttr := "1",
          controlled(
            value <-- opacity.signal.map(o => (o(f.id) * 100).toInt.toString),
            onInput.mapToValue --> { v =>
              val o = v.toDouble / 100; opacity.update(_ + (f.id -> o));
              dispatch(ViewerAction.SetOpacity(LayerId.unsafe(f.id), LayerOpacity.unsafe(o)))
            }
          )
        )
      )

    div(
      cls := "revision",
      h1(m.title),
      p(
        cls := "meta",
        s"revision ${loaded.detail.id} · digest ${loaded.detail.digest.take(19)}… · parent ${loaded.detail.parent.getOrElse("(none)")}"
      ),
      div(
        cls := "workspace",
        div(
          cls := "navigator",
          h2("Layers"),
          overlayFields.map(layerRow)
        ),
        div(
          cls := "canvas-host",
          idAttr := "volume",
          RendererHost.pane(host, h => { handle = Some(h); syncAll() })
        )
      ),
      m.warnings.headOption.map(w =>
        p(cls := "warning", w.hcursor.downField("message").as[String].getOrElse(""))
      )
    )
