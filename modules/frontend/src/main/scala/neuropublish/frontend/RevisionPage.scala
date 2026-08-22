package neuropublish.frontend

import com.raquo.laminar.api.L.*
import intaglio.{ColorRamp, DeviceContext, DisplayWindow, Rgba32, ScalarColorizer}
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
  * ScalaFIM volume host. Plain DOM; layer visibility, order and opacity only. No visual system yet
  * (plan: design cadence).
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

    def scalarLayer(id: String, vol: NeuroVol[Double], lo: Double, hi: Double, ramp: ColorRamp) =
      SliceLayer(
        LayerId.unsafe(id),
        vol,
        SliceSampling.Nearest(0.0),
        ScalarColorizer(DisplayWindow.unsafe(lo, hi), ramp)
      )

    val underlayLayer =
      scalarLayer(underlay.get.asset, underlayVol, 0.0, 100.0, ColorRamp.Grayscale)
    val overlayLayers = overlayFields.flatMap(f =>
      f.representations.find(_.kind == "volume").flatMap(r => loaded.volumes.get(r.asset)).map(v =>
        f -> scalarLayer(f.id, v, -8.0, 8.0, ColorRamp(cold, hot))
      )
    )
    val model = ViewerModel.unsafe(space, (underlayLayer +: overlayLayers.map(_._2)).toVector)

    // published recommendation: t and z visible by default, effect / se hidden
    val initiallyVisible = overlayFields.filter(f =>
      f.measure.endsWith("/t-statistic") || f.measure.endsWith("/z-statistic")
    ).map(_.id).toSet
    val visibility = Var(overlayFields.map(f => f.id -> initiallyVisible.contains(f.id)).toMap)
    val opacity = Var(overlayFields.map(f => f.id -> 0.85).toMap)
    val order = Var(overlayFields.map(_.id))
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
    def sync(): Unit =
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
            onClick.mapToChecked --> { v => visibility.update(_ + (f.id -> v)); sync() }
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
            onInput.mapToValue --> { v => opacity.update(_ + (f.id -> v.toDouble / 100)); sync() }
          )
        ),
        button(
          "↑",
          onClick --> { _ =>
            order.update(o => {
              val i = o.indexOf(f.id); if i > 0 then o.patch(i - 1, List(f.id, o(i - 1)), 2) else o
            })
          }
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
          children <--
            order.signal.map(ids => ids.flatMap(id => overlayFields.find(_.id == id)).map(layerRow))
        ),
        div(
          cls := "canvas-host",
          idAttr := "volume",
          RendererHost.pane(host, h => { handle = Some(h); sync() })
        )
      ),
      m.warnings.headOption.map(w =>
        p(cls := "warning", w.hcursor.downField("message").as[String].getOrElse(""))
      )
    )
