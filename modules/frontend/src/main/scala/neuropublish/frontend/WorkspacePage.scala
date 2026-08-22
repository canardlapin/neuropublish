package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.protocol.Measures
import neuropublish.protocol.json.*
import neuropublish.viewer.*
import neuropublish.viewer.laminar.RendererHost
import org.scalajs.dom
import scalafim.image.view.LayerSampleValue

/** The Volume scientific workspace: navigator · canvas · inspector, status bar (product
  * definition).
  */
object WorkspacePage:
  def render(store: WorkspaceStore, onStateChange: Workspace => Unit): HtmlElement =
    val L = store.loaded; val m = L.manifest
    val ws = store.state.signal
    ws.foreach(onStateChange)(using unsafeWindowOwner)

    def fmt(d: Double) = if d.abs >= 100 then f"$d%.0f" else f"$d%.2f"

    // ---- navigator: analysis → estimand → measure ----
    val navigator = navTag(
      cls := "navigator",
      aria.label := "Results",
      h2("Results"),
      m.analyses.map { a =>
        div(
          cls := "tree-analysis",
          div(
            cls := "tree-row analysis",
            span(a.label),
            a.sampleSize.map(n => span(cls := "muted", s"n = $n"))
          ),
          a.estimands.sortBy(_.order.getOrElse(Int.MaxValue)).map { e =>
            val fields = L.volumeFields.filter(_.estimand == e.id)
            div(
              cls := "tree-estimand",
              div(cls := "tree-row estimand", e.label),
              fields.map { f =>
                button(
                  cls := "tree-row measure",
                  dataAttr("field") := f.id,
                  cls.toggle("selected") <--
                    ws.map(_.layers.find(_.id == f.id).exists(_.current.visible)),
                  aria.pressed <--
                    ws.map(_.layers.find(_.id == f.id).exists(_.current.visible)).map(_.toString),
                  span(Measures.label(f.measure)),
                  span(cls := "muted short", Measures.short(f.measure)),
                  onClick --> { _ =>
                    val vis = store.state.now().layers.find(_.id == f.id).exists(_.current.visible)
                    store.dispatch(Workspace.Action.SetVisible(f.id, !vis));
                    store.dispatch(Workspace.Action.SetInspector("layers"))
                  }
                )
              }
            )
          }
        )
      }
    )

    // ---- layer card ----
    def num(name: String, sig: Signal[Double], onChange: Double => Unit, stepV: String = "0.1") =
      label(
        cls := "field",
        span(name),
        input(
          typ := "number",
          stepAttr := stepV,
          controlled(
            value <-- sig.map(fmt),
            onInput.mapToValue --> { v => v.toDoubleOption.foreach(onChange) }
          )
        )
      )

    def layerCard(f: ResultField) =
      val layer = ws.map(_.layers.find(_.id == f.id))
      val cur = layer.map(_.map(_.current))
      val sm = L.summaries.get(L.assetOf(f))
      div(
        cls := "layer-card",
        dataAttr("layer") := f.id,
        cls.toggle("hidden") <-- cur.map(_.exists(!_.visible)),
        div(
          cls := "layer-head",
          input(
            typ := "checkbox",
            aria.label := s"show ${Measures.label(f.measure)}",
            controlled(
              checked <-- cur.map(_.exists(_.visible)),
              onClick.mapToChecked --> (v => store.dispatch(Workspace.Action.SetVisible(f.id, v)))
            )
          ),
          div(
            cls := "layer-title",
            span(m.analyses.flatMap(
              _.estimands
            ).find(_.id == f.estimand).map(_.label).getOrElse(f.estimand)),
            span(cls := "muted", s" · ${Measures.label(f.measure)}")
          ),
          button(
            cls := "ghost",
            title := "move up (draw later)",
            aria.label := "move layer up",
            "↑",
            onClick --> (_ => store.dispatch(Workspace.Action.MoveUp(f.id)))
          ),
          button(
            cls := "ghost",
            title := "move down",
            aria.label := "move layer down",
            "↓",
            onClick --> (_ => store.dispatch(Workspace.Action.MoveDown(f.id)))
          ),
          child <-- layer.map(_.exists(_.modified)).map(mod =>
            if mod then
              button(
                cls := "pill warn reset",
                "modified · reset",
                onClick --> (_ => store.dispatch(Workspace.Action.ResetLayer(f.id)))
              )
            else span(cls := "pill accent", "published")
          )
        ),
        label(
          cls := "field",
          span("opacity"),
          input(
            typ := "range",
            minAttr := "0",
            maxAttr := "100",
            stepAttr := "1",
            aria.label := "opacity",
            controlled(
              value <-- cur.map(_.map(c => (c.opacity * 100).round.toString).getOrElse("85")),
              onInput.mapToValue -->
                (v => store.dispatch(Workspace.Action.SetOpacity(f.id, v.toDouble / 100)))
            )
          )
        ),
        div(
          cls := "group",
          div(cls := "k", "Visible values"),
          label(
            cls := "field",
            span("mode"),
            select(
              controlled(
                value <-- cur.map(_.map(_.threshold.mode).getOrElse("off")),
                onChange.mapToValue -->
                  (v =>
                    store.dispatch(Workspace.Action.SetThreshold(
                      f.id,
                      Threshold(
                        v,
                        store.state.now().layers.find(
                          _.id == f.id
                        ).map(_.current.threshold.min).getOrElse(0.0)
                      )
                    ))
                  )
              ),
              option(value := "two-sided", "two-sided"),
              option(value := "off", "off")
            )
          ),
          num(
            "minimum |value|",
            cur.map(_.map(_.threshold.min).getOrElse(0.0)),
            v =>
              store.dispatch(Workspace.Action.SetThreshold(
                f.id,
                Threshold(
                  store.state.now().layers.find(
                    _.id == f.id
                  ).map(_.current.threshold.mode).getOrElse("two-sided"),
                  v
                )
              ))
          )
        ),
        div(
          cls := "group",
          div(cls := "k", "Colour scale"),
          num(
            "minimum",
            cur.map(_.map(_.window.min).getOrElse(0.0)),
            v =>
              store.dispatch(Workspace.Action.SetWindow(
                f.id,
                Window(
                  v,
                  store.state.now().layers.find(
                    _.id == f.id
                  ).map(_.current.window.max).getOrElse(v + 1)
                )
              ))
          ),
          num(
            "maximum",
            cur.map(_.map(_.window.max).getOrElse(1.0)),
            v =>
              store.dispatch(Workspace.Action.SetWindow(
                f.id,
                Window(
                  store.state.now().layers.find(
                    _.id == f.id
                  ).map(_.current.window.min).getOrElse(v - 1),
                  v
                )
              ))
          ),
          label(
            cls := "field",
            span("colormap"),
            select(
              controlled(
                value <-- cur.map(_.map(_.colormap).getOrElse("cold-hot")),
                onChange.mapToValue --> (v => store.dispatch(Workspace.Action.SetColormap(f.id, v)))
              ),
              Colormaps.all.map((id, name) => option(value := id, name))
            )
          ),
          div(
            cls := "ramp",
            styleAttr <--
              cur.map(c => s"background:${Colormaps.css(c.map(_.colormap).getOrElse("cold-hot"))}")
          )
        ),
        sm.map { s =>
          div(
            cls := "summary",
            div(
              cls := "hist",
              s.histogram.map(h =>
                div(
                  cls := "bar",
                  height := s"${math.max(1, (100.0 * h / math.max(1, s.histogram.max)).toInt)}%"
                )
              )
            ),
            div(
              cls := "muted mono",
              s"min ${fmt(s.min)} · max ${fmt(s.max)} · median ${fmt(s.quantiles(3))} · missing ${s.missing} · zero ${s.zero}"
            )
          )
        }
      )

    // ---- inspector tabs ----
    val tabs = List("layers" -> "Layers", "analysis" -> "Analysis", "provenance" -> "Provenance")
    val inspector = asideTag(
      cls := "inspector",
      aria.label := "Inspector",
      div(
        cls := "tabs",
        role := "tablist",
        tabs.map((id, name) =>
          button(
            role := "tab",
            cls := "tab",
            cls.toggle("active") <-- ws.map(_.inspector == id),
            aria.selected <-- ws.map(_.inspector == id),
            name,
            onClick --> (_ => store.dispatch(Workspace.Action.SetInspector(id)))
          )
        )
      ),
      child <-- ws.map(_.inspector).distinct.map {
        case "analysis" => analysisPanel(L)
        case "provenance" => provenancePanel(L)
        case _ => div(
            cls := "layers",
            div(
              cls := "row",
              button(
                cls := "ghost",
                "Reset all to published",
                onClick --> (_ => store.dispatch(Workspace.Action.ResetAll))
              )
            ),
            children <-- ws.map(_.layers.map(_.id)).distinct.map(ids =>
              ids.flatMap(id => L.volumeFields.find(_.id == id)).map(layerCard)
            )
          )
      }
    )

    // ---- canvas + status ----
    val canvasPane = div(
      cls := "canvas-host",
      idAttr := "volume",
      RendererHost.pane(store.host, h => store.attach(h.renderer)),
      onClick --> { e =>
        val r = e.currentTarget.asInstanceOf[dom.HTMLElement].getBoundingClientRect();
        store.pick(e.clientX - r.left, e.clientY - r.top)
      },
      onWheel.preventDefault --> { e =>
        val r = e.currentTarget.asInstanceOf[dom.HTMLElement].getBoundingClientRect();
        store.scroll(e.clientX - r.left, e.clientY - r.top, if e.deltaY > 0 then 1 else -1)
      }
    )
    val status = div(
      cls := "status-bar",
      role := "status",
      child <-- store.readout.signal.map {
        case None => span(cls := "muted", "click the canvas to set the cursor")
        case Some(r) => div(
            cls := "readout",
            span(
              cls := "readout-layer",
              dataAttr("readout") := "world",
              span(cls := "k", "RAS+"),
              span(cls := "mono", s"${fmt(r.world.x)}, ${fmt(r.world.y)}, ${fmt(r.world.z)}")
            ),
            r.layers.map { lr =>
              val id = lr.layer.toString
              val label = L.volumeFields.find(_.id == id).map(f =>
                Measures.short(f.measure)
              ).getOrElse(m.underlays.headOption.map(_.label).getOrElse(id))
              span(
                cls := "readout-layer",
                dataAttr("readout") := id,
                span(cls := "k", label),
                span(
                  cls := "mono",
                  lr.value match
                    case LayerSampleValue.Scalar(v) => fmt(v)
                    case LayerSampleValue.Label(l) => l.toString
                    case LayerSampleValue.Mask(b) => if b then "in" else "out"
                )
              )
            }
          )
      }
    )

    div(
      cls := "page workspace-page",
      dataAttr("preset") <-- ws.map(_.layout.preset.toString.toLowerCase),
      headerTag(
        cls := "topbar",
        a(href := s"/w/${L.workspace}/p/${L.project}", cls := "crumb", L.project),
        span(cls := "crumb muted", "/"),
        span(cls := "crumb mono", L.detail.id),
        span(cls := "crumb muted", "/"),
        span(cls := "crumb", m.title),
        div(cls := "spacer"),
        button(
          cls := "ghost",
          "Reset view",
          onClick --> (_ => store.dispatch(Workspace.Action.ResetAll))
        )
      ),
      div(cls := "workspace", navigator, canvasPane, inspector),
      status,
      m.warnings.headOption.map(w =>
        div(cls := "callout warn", w.hcursor.downField("message").as[String].getOrElse(""))
      )
    )

  private def analysisPanel(L: Loaded) =
    val m = L.manifest
    div(
      cls := "facts-panel",
      m.analyses.map { a =>
        div(
          cls := "group",
          div(cls := "k", "Analysis"),
          div(a.label),
          a.sampleSize.map(n =>
            div(cls := "fact", span(cls := "k", "sample size"), span(s"n = $n"))
          ),
          a.method.map(j =>
            div(
              cls := "fact",
              span(cls := "k", "method"),
              span(
                cls := "mono",
                j.hcursor.downField(
                  "schema"
                ).downField("id").as[String].getOrElse("(unknown record)") + " @ " +
                  j.hcursor.downField("schema").downField("version").as[String].getOrElse("")
              )
            )
          ),
          div(
            cls := "fact",
            span(cls := "k", "estimands"),
            span(a.estimands.map(_.label).mkString(", "))
          ),
          div(
            cls := "fact",
            span(cls := "k", "inference"),
            span(
              "No correction record is declared; the display threshold is not an inferential decision."
            )
          )
        )
      },
      div(
        cls := "group",
        div(cls := "k", "Publication"),
        div(cls := "fact", span(cls := "k", "revision"), span(cls := "mono", L.detail.id)),
        div(cls := "fact", span(cls := "k", "digest"), span(cls := "mono", L.detail.digest)),
        div(
          cls := "fact",
          span(cls := "k", "parent"),
          span(cls := "mono", L.detail.parent.getOrElse("(none)"))
        ),
        div(
          cls := "fact",
          span(cls := "k", "committed"),
          span(L.detail.committedAt.take(19).replace("T", " "))
        )
      )
    )

  private def provenancePanel(L: Loaded) =
    val prov = L.manifest.raw.hcursor.downField("provenance")
    val activities = prov.downField("activities").values.map(_.toList).getOrElse(Nil)
    val entities = prov.downField("entities").values.map(_.toList).getOrElse(Nil)
    div(
      cls := "facts-panel",
      div(cls := "k", "Pipeline"),
      entities.map(e =>
        div(
          cls := "prov-node entity",
          e.hcursor.downField(
            "label"
          ).as[String].getOrElse(e.hcursor.downField("id").as[String].getOrElse("")),
          if e.hcursor.downField("hosted").as[Boolean].contains(false) then
            span(cls := "pill", "not hosted")
          else emptyNode
        )
      ),
      activities.map { a =>
        val sid = a.hcursor.downField("schema").downField("id").as[String].getOrElse("")
        val known = sid.startsWith("org.bbuchsbaum.") || sid.startsWith("org.neuropublish.")
        div(
          cls := "prov-node activity",
          cls.toggle("unknown") := !known,
          div(
            span(a.hcursor.downField("id").as[String].getOrElse("")),
            if known then emptyNode else span(cls := "pill", "retained, not interpreted")
          ),
          div(
            cls := "mono muted",
            sid + " @ " +
              a.hcursor.downField("schema").downField("version").as[String].getOrElse("")
          ),
          div(
            cls := "mono muted small",
            a.hcursor.downField("payload").focus.map(_.noSpaces).getOrElse("")
          )
        )
      },
      L.manifest.warnings.map(w =>
        div(cls := "callout warn", w.hcursor.downField("message").as[String].getOrElse(""))
      )
    )
