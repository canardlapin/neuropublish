package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.api.SavedViewDetail
import neuropublish.protocol.Measures
import neuropublish.protocol.json.*
import neuropublish.viewer.*
import neuropublish.viewer.laminar.{RendererHost, SurfaceHost}
import org.scalajs.dom
import scala.scalajs.js
import scalafim.image.view.LayerSampleValue

/** The scientific workspace: navigator · canvas · inspector, status bar (product definition). The
  * centre follows the layout preset (ADR 0002): Volume = triplanar; Surface = bilateral surfaces;
  * Hybrid = volume and surface panes side by side behind a keyboard-operable divider. The three
  * presets share one `WorkspaceStore`, so switching never changes the result identity.
  */
object WorkspacePage:
  def render(store: WorkspaceStore, onStateChange: Workspace => Unit, mode: PageMode): HtmlElement =
    val L = store.loaded
    val m = L.manifest
    val ws = store.state.signal
    val dialog = Var[Option[HtmlElement]](None)
    val savedView = Var[Option[SavedViewDetail]](mode match
      case PageMode.Explore(_, s) => s
      case _ => None)
    // fetched once per page, on first open of the Provenance tab
    lazy val provenance: HtmlElement = mode match
      case PageMode.Explore(session, _) =>
        ProvenancePanel.render(session.api.provenance(L.detail.id))
      case PageMode.Presentation(_, _) => ProvenancePanel.unavailable(L)

    def fmt(d: Double) = if d.abs >= 100 then f"$d%.0f" else f"$d%.2f"
    def layerOf(id: String) = store.state.now().layers.find(_.id == id)

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
            val fields = L.fields.filter(_.estimand == e.id)
            div(
              cls := "tree-estimand",
              div(cls := "tree-row estimand", e.label),
              fields.map { f =>
                val visible = ws.map(_.layers.find(_.id == f.id).exists(_.current.visible))
                val reps = L.representationsOf(f)
                button(
                  cls := "tree-row measure",
                  dataAttr("field") := f.id,
                  cls.toggle("selected") <-- visible,
                  aria.pressed <-- visible.map(_.toString),
                  span(Measures.label(f.measure)),
                  span(cls := "muted short", Measures.short(f.measure)),
                  span(
                    cls := "reps muted",
                    title := "representations",
                    dataAttr("reps") :=
                      (if reps.volume then "v" else "") + (if reps.surface then "s" else ""),
                    ((if reps.volume then List("vol") else Nil) ++
                      reps.surfaces.toList.sorted.map(h => s"surf ${h.take(1).toUpperCase}"))
                      .mkString(" ")
                  ),
                  onClick --> { _ =>
                    val vis = layerOf(f.id).exists(_.current.visible)
                    store.dispatch(Workspace.Action.SetVisible(f.id, !vis))
                    store.dispatch(Workspace.Action.SetInspector("layers"))
                  }
                )
              }
            )
          }
        )
      }
    )

    /** Numeric field: the user types freely; the value commits on change (Enter/blur). Rejected
      * values are marked invalid.
      */
    def num(name: String, sig: Signal[Double], commit: Double => Boolean, stepV: String = "0.1") =
      val invalid = Var(false)
      label(
        cls := "field",
        span(name),
        input(
          typ := "number",
          stepAttr := stepV,
          aria.invalid <-- invalid.signal.map(_.toString),
          cls.toggle("invalid") <-- invalid.signal,
          value <-- sig.map(fmt),
          onChange.mapToValue --> { v => invalid.set(!v.toDoubleOption.exists(commit)) }
        )
      )

    /** The maximum magnitude: empty means none. It is offered only where the renderer can honour it
      * — `two-sided` with a positive minimum (see `Threshold`) — and elsewhere says why rather than
      * accepting a number that would be dropped on the way to the canvas.
      */
    def maxMagnitude(f: ResultField, cur: Signal[Option[LayerDisplay]]) =
      val invalid = Var(false)
      val boundable = cur.map(_.map(_.threshold).exists(Threshold.boundable))
      label(
        cls := "field",
        span("maximum |value|"),
        input(
          typ := "number",
          stepAttr := "0.1",
          placeholder := "none",
          disabled <-- boundable.map(!_),
          title <-- boundable.map(b =>
            if b then "hide values whose magnitude exceeds this"
            else "a maximum magnitude needs a two-sided mode with a positive minimum"
          ),
          aria.invalid <-- invalid.signal.map(_.toString),
          cls.toggle("invalid") <-- invalid.signal,
          value <-- cur.map(_.flatMap(_.threshold.max).map(fmt).getOrElse("")),
          onChange.mapToValue --> { v =>
            val t = layerOf(f.id).map(_.current.threshold).getOrElse(Threshold("off", 0.0))
            if v.trim.isEmpty then
              invalid.set(false)
              store.dispatch(Workspace.Action.SetThreshold(f.id, t.copy(max = None)))
            else
              invalid.set(!v.toDoubleOption.exists(x =>
                store.tryDispatch(Workspace.Action.SetThreshold(f.id, t.copy(max = Some(x))))
              ))
          }
        )
      )

    /** Where the field is drawn, and — the product rule — where it is not. A one-hemisphere field
      * says so ("left surface only; no right-hemisphere representation"); a representation on a
      * surface the pane could not place says that too. Absence is stated, never projected.
      */
    def drawnIn(f: ResultField, reps: LayerRepresentations): String =
      val bothPlaced = L.surface("left").isDefined && L.surface("right").isDefined
      val alone = bothPlaced && reps.surfaces.size == 1
      val drawn =
        (List(Option.when(reps.volume)("volume")) ++
          List("left", "right").map(h =>
            Option.when(reps.surfaces(h))(s"$h surface${if alone then " only" else ""}")
          )).flatten
      val only =
        if !alone then None
        else
          reps.surfaces.headOption.map(h =>
            s"${if h == "left" then "right" else "left"}-hemisphere representation"
          )
      val undrawn = L.undrawnSurfaceRepsOf(f).map(_.surface).distinct
      val parts = List(
        Option.when(drawn.nonEmpty)(drawn.mkString(" · ")),
        Option.when(drawn.isEmpty)("nowhere: no ready representation"),
        only.map(o => s"no $o"),
        Option.when(undrawn.nonEmpty)(
          s"not drawn on ${undrawn.mkString(", ")} (one surface per hemisphere is placed)"
        )
      ).flatten
      parts.mkString("; ")

    /** What produced the surface values. An absent `derivation` is a declaration, not a gap: the
      * producer states a native surface measurement (SPEC §5, "Deliberately not changed").
      */
    def derivationLine(f: ResultField, reps: LayerRepresentations) =
      Option.when(reps.surface) {
        val stated = L.surfaceRepsOf(f).flatMap(r =>
          L.derivationOf(f, r.surface).map((id, schema, line) =>
            s"$id · $schema${line.fold("")(l => s" · $l")}"
          )
        ).distinct
        div(
          cls := "layer-derivation muted small",
          dataAttr("testid") := "layer-derivation",
          if stated.isEmpty then
            "surface values: native surface measurement; no projection receipt declared"
          else s"surface values from ${stated.mkString("; ")}"
        )
      }

    /** One card per layer; identical controls whatever the representations: the store mirrors each
      * change into every pane the field is drawn in.
      */
    def layerCard(f: ResultField) =
      val layer = ws.map(_.layers.find(_.id == f.id))
      val cur = layer.map(_.map(_.current))
      val sm = L.summaryOf(f)
      val reps = L.representationsOf(f)
      val estimandLabel =
        m.analyses.flatMap(_.estimands).find(_.id == f.estimand).map(_.label).getOrElse(f.estimand)
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
            span(estimandLabel),
            span(cls := "muted", s" · ${Measures.label(f.measure)}")
          ),
          button(
            cls := "ghost",
            title := "move up (drawn on top)",
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
          child <-- layer.map(l => (l.exists(_.modified), l.exists(_.recommended))).map {
            (mod, rec) =>
              if mod then
                button(
                  cls := "pill warn reset",
                  "modified · reset",
                  onClick --> (_ => store.dispatch(Workspace.Action.ResetLayer(f.id)))
                )
              else if rec then span(cls := "pill accent", "published")
              else
                span(
                  cls := "pill",
                  title := "no display recommendation in the manifest; window from the data range",
                  "default"
                )
          }
        ),
        div(
          cls := "layer-reps muted small",
          dataAttr("testid") := "layer-representations",
          "drawn in: ",
          drawnIn(f, reps)
        ),
        derivationLine(f, reps),
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
                      Threshold(v, layerOf(f.id).map(_.current.threshold.min).getOrElse(0.0))
                    ))
                  )
              ),
              option(value := "two-sided", "two-sided"),
              option(value := "positive", "positive"),
              option(value := "negative", "negative"),
              option(value := "off", "off")
            )
          ),
          num(
            "minimum |value|",
            cur.map(_.map(_.threshold.min).getOrElse(0.0)),
            v =>
              // carried from the current threshold, so raising the minimum keeps the maximum
              // rather than quietly discarding it
              val t = layerOf(f.id).map(_.current.threshold).getOrElse(Threshold("two-sided", 0.0))
              store.tryDispatch(Workspace.Action.SetThreshold(f.id, t.copy(min = v)))
          ),
          maxMagnitude(f, cur)
        ),
        div(
          cls := "group",
          div(cls := "k", "Colour scale"),
          num(
            "minimum",
            cur.map(_.map(_.window.min).getOrElse(0.0)),
            v =>
              store.tryDispatch(Workspace.Action.SetWindow(
                f.id,
                Window(v, layerOf(f.id).map(_.current.window.max).getOrElse(v + 1))
              ))
          ),
          div(
            cls := "field",
            span("centre"),
            span(
              cls := "mono",
              dataAttr("testid") := "window-centre",
              title :=
                "derived: this version renders one linear ramp, so the colour scale is centred on the window midpoint",
              child.text <-- cur.map(c =>
                fmt(c.map(d => (d.window.min + d.window.max) / 2.0).getOrElse(0.0))
              )
            )
          ),
          num(
            "maximum",
            cur.map(_.map(_.window.max).getOrElse(1.0)),
            v =>
              store.tryDispatch(Workspace.Action.SetWindow(
                f.id,
                Window(layerOf(f.id).map(_.current.window.min).getOrElse(v - 1), v)
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
        case "provenance" => provenance
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
            // keyed by layer id so a reorder moves the existing card (focus survives) instead of re-creating it
            children <-- ws.map(_.layers.map(_.id)).distinct.split(identity)((id, _, _) =>
              L.field(id).map(layerCard).getOrElse(emptyNode)
            )
          )
      }
    )

    // ---- link state: what the surface pane says about the shared cursor ----
    /** Visible layers that have no surface representation: named honestly, never projected. */
    val absentOnSurface: Signal[List[(String, String)]] = ws.map(_.layers.filter(l =>
      l.current.visible && !l.representations.surface
    ).flatMap(l => L.field(l.id).map(f => (f.id, L.labelOf(f)))).toList)
    val linkText: Signal[Option[String]] = store.link.signal.map(l =>
      // spaces that disagree are never bridged by a distance: the reason replaces the number
      store.spaceMismatch.orElse(l.map {
        case SurfaceHost.Link.Linked(_, v, d) => f"linked to vertex $v, $d%.1f mm"
        case SurfaceHost.Link.OutOfRange(_) =>
          f"no vertex within ${SurfaceHost.DefaultLinkRadius.value}%.0f mm"
        case SurfaceHost.Link.NoGeometry => "no surface geometry"
      })
    )

    // ---- panes ----
    /** Below 900 px the Hybrid panes stack and the divider becomes horizontal (np.css). */
    val narrow = dom.window.matchMedia("(max-width: 900px)")
    val stacked = Var(narrow.matches)
    val onNarrow: js.Function1[dom.Event, Unit] =
      e => stacked.set(e.asInstanceOf[js.Dynamic].matches.asInstanceOf[Boolean])

    val volumePane = div(
      cls := "canvas-host pane volume-pane",
      idAttr := "volume",
      dataAttr("pane") := "volume",
      RendererHost.pane(store.host, h => store.attach(h.renderer), _ => store.detach()),
      child.maybe <--
        store.cursorSource.signal.combineWith(store.surfaceReadout.signal.distinct).map {
          (src, sr) =>
            Option.when(src.contains("surface"))(
              div(
                cls := "pane-badge",
                dataAttr("testid") := "volume-link",
                sr.map(r => s"cursor from surface vertex ${r.vertex}").getOrElse(
                  "cursor from surface"
                )
              )
            )
        },
      onClick --> { e =>
        val r = e.currentTarget.asInstanceOf[dom.HTMLElement].getBoundingClientRect();
        store.pick(e.clientX - r.left, e.clientY - r.top)
      },
      onWheel.preventDefault --> { e =>
        val r = e.currentTarget.asInstanceOf[dom.HTMLElement].getBoundingClientRect();
        store.scroll(e.clientX - r.left, e.clientY - r.top, if e.deltaY > 0 then 1 else -1)
      }
    )

    val cameraBar = div(
      cls := "surface-toolbar",
      label(
        cls := "field inline",
        span("view from"),
        select(
          dataAttr("testid") := "surface-viewpoint",
          aria.label := "surface viewpoint",
          controlled(
            value <-- ws.map(_.surfaceCamera.viewpoint),
            onChange.mapToValue -->
              (v =>
                store.dispatch(Workspace.Action.SetSurfaceCamera(
                  store.state.now().surfaceCamera.copy(viewpoint = v)
                ))
              )
          ),
          SurfaceCameraState.Viewpoints.map(v => option(value := v, v))
        )
      ),
      label(
        cls := "field inline",
        span("projection"),
        select(
          dataAttr("testid") := "surface-projection",
          aria.label := "surface projection",
          controlled(
            value <-- ws.map(_.surfaceCamera.projection),
            onChange.mapToValue -->
              (v =>
                store.dispatch(Workspace.Action.SetSurfaceCamera(
                  store.state.now().surfaceCamera.copy(projection = v)
                ))
              )
          ),
          SurfaceCameraState.Projections.map(p => option(value := p, p))
        )
      )
    )

    val surfaceBody: Seq[Modifier[HtmlElement]] =
      if store.hasSurfaces then
        Seq(
          cameraBar,
          div(
            cls := "surface-canvas",
            RendererHost.pane(
              store.surfaceHost,
              h => store.attachSurface(h.renderer),
              _ => store.detachSurface()
            ),
            onClick --> { e =>
              val r = e.currentTarget.asInstanceOf[dom.HTMLElement].getBoundingClientRect();
              store.pickSurface(e.clientX - r.left, e.clientY - r.top): Unit
            }
          ),
          child.maybe <-- store.surfaceError.signal.map(_.map(m =>
            div(cls := "pane-empty error", dataAttr("testid") := "surface-error", m)
          )),
          child.maybe <-- absentOnSurface.map(names =>
            Option.when(names.nonEmpty)(
              div(
                cls := "pane-empty",
                dataAttr("testid") := "surface-empty",
                names.map((id, n) =>
                  div(dataAttr("field") := id, s"$n has no surface representation")
                )
              )
            )
          ),
          child.maybe <-- linkText.combineWith(store.cursorSource.signal).map { (t, src) =>
            t.filter(_ => !src.contains("surface")).map(s =>
              div(cls := "pane-badge", dataAttr("testid") := "surface-link", s)
            )
          }
        )
      else
        Seq(
          div(
            cls := "pane-empty",
            dataAttr("testid") := "surface-empty",
            if store.unplaceableSurfaces.isEmpty && store.unplacedSurfaces.isEmpty then
              div("This revision declares no surfaces.")
            else if store.unplaceableSurfaces.isEmpty then
              div(
                "This revision's surfaces share a hemisphere slot: " +
                  store.unplacedSurfaces.map(_.id).mkString(", ") +
                  " are not placed. One surface per hemisphere is displayed."
              )
            else
              div(
                "This revision's surfaces cannot be placed: " +
                  store.unplaceableSurfaces.map(d =>
                    s"${d.id} declares hemisphere '${d.hemisphere}'"
                  ).mkString("; ") + ". Only left and right hemispheres are displayed."
              )
            ,
            child <-- absentOnSurface.map(names =>
              div(names.map((id, n) =>
                div(dataAttr("field") := id, s"$n has no surface representation")
              ))
            )
          )
        )
    val surfacePane = div(
      cls := "canvas-host pane surface-pane",
      idAttr := "surface",
      dataAttr("pane") := "surface",
      surfaceBody
    )

    /** Hybrid divider: pointer drag or arrow keys (2 % steps); `aria-valuenow` is the volume pane's
      * share in percent.
      */
    val splitter =
      def move(fraction: Double) =
        store.dispatch(Workspace.Action.Layout(WorkspaceLayout.Action.ResizeSplit(fraction)))
      // set while a pointer drag is in flight; called on unmount so a divider that disappears
      // mid-drag (a preset switch from a keyboard shortcut) leaves no window listener behind
      var endDrag: () => Unit = () => ()
      div(
        cls := "splitter",
        role := "separator",
        tabIndex := 0,
        dataAttr("testid") := "hybrid-splitter",
        aria.orientation <-- stacked.signal.map(if _ then "horizontal" else "vertical"),
        aria.label := "resize volume and surface panes",
        aria.valueMin := 5.0,
        aria.valueMax := 90.0,
        aria.valueNow <-- ws.map(w => (w.layout.splitFraction * 100).round.toDouble),
        onKeyDown --> { e =>
          val f = store.state.now().layout.splitFraction
          e.key match
            case "ArrowLeft" | "ArrowDown" => e.preventDefault(); move(f - 0.02)
            case "ArrowRight" | "ArrowUp" => e.preventDefault(); move(f + 0.02)
            case "Home" => e.preventDefault(); move(0.05)
            case "End" => e.preventDefault(); move(0.9)
            case _ => ()
        },
        // a drag in flight owns the pointer (the panes' own handlers never see it) and its
        // listeners are released on pointer-up, on cancel, and if the divider unmounts mid-drag
        onPointerDown --> { e =>
          e.preventDefault()
          val handle = e.currentTarget.asInstanceOf[dom.HTMLElement]
          val box = handle.parentElement.getBoundingClientRect()
          val vertical = stacked.now()
          val pointerId = e.pointerId
          // pointer capture is not in scala-js-dom's HTMLElement facade
          val capture = handle.asInstanceOf[js.Dynamic]
          try capture.applyDynamic("setPointerCapture")(pointerId)
          catch case _: Throwable => () // capture is a nicety; the drag works without it
          var onMove: js.Function1[dom.PointerEvent, Unit] = null
          var onUp: js.Function1[dom.PointerEvent, Unit] = null
          onMove = ev =>
            move(
              if vertical then (ev.clientY - box.top) / box.height
              else (ev.clientX - box.left) / box.width
            )
          onUp = _ => {
            dom.window.removeEventListener("pointermove", onMove)
            dom.window.removeEventListener("pointerup", onUp)
            dom.window.removeEventListener("pointercancel", onUp)
            endDrag = () => ()
            try
              if capture.applyDynamic("hasPointerCapture")(pointerId).asInstanceOf[Boolean] then
                capture.applyDynamic("releasePointerCapture")(pointerId)
            catch case _: Throwable => ()
          }
          endDrag = () => onUp(null)
          dom.window.addEventListener("pointermove", onMove)
          dom.window.addEventListener("pointerup", onUp)
          dom.window.addEventListener("pointercancel", onUp)
        },
        onUnmountCallback(_ => endDrag())
      )

    /** The centre follows the preset; panes mount and unmount through their hosts' lifecycle. */
    val centre = div(
      cls := "centre",
      dataAttr("testid") := "centre",
      styleAttr <-- ws.combineWith(stacked.signal).map { (w, st) =>
        w.layout.preset match
          case LayoutPreset.Hybrid if st =>
            val f = w.layout.splitFraction
            // rows need a floor: the centre's own height is only its min-height, and a 0-floored
            // fr row would let the volume pane overflow into the surface pane
            s"grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(280px, ${f}fr) 8px minmax(280px, ${1 -
                f}fr)"
          case LayoutPreset.Hybrid =>
            val f = w.layout.splitFraction
            s"grid-template-columns: minmax(0, ${f}fr) 8px minmax(0, ${1 - f}fr); grid-template-rows: minmax(0, 1fr)"
          case _ => "grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr)"
      },
      onMountCallback(_ => narrow.addEventListener("change", onNarrow)),
      onUnmountCallback(_ => narrow.removeEventListener("change", onNarrow)),
      children <-- ws.map(_.layout.preset).distinct.map {
        case LayoutPreset.Volume => List(volumePane)
        case LayoutPreset.Surface => List(surfacePane)
        case LayoutPreset.Hybrid => List(volumePane, splitter, surfacePane)
      }
    )

    val presetSwitch = div(
      cls := "segmented",
      role := "radiogroup",
      aria.label := "workspace preset",
      LayoutPreset.values.toList.map { p =>
        val id = p.toString.toLowerCase
        button(
          role := "radio",
          cls := "seg",
          dataAttr("testid") := s"preset-$id",
          cls.toggle("active") <-- ws.map(_.layout.preset == p),
          aria.checked <-- ws.map(w => (w.layout.preset == p).toString),
          p.toString,
          onClick -->
            (_ =>
              store.dispatch(Workspace.Action.Layout(WorkspaceLayout.Action.SetPreset(p)))
            )
        )
      }
    )

    // ---- status bar: per-pane readouts and the link distance ----
    val status = div(
      cls := "status-bar",
      role := "status",
      child <-- store.readout.signal.distinct.combineWith(
        store.surfaceReadout.signal.distinct,
        linkText.distinct,
        ws.map(_.layout.preset)
      ).map { (r, sr, lt, preset) =>
        if r.isEmpty && sr.isEmpty then span(cls := "muted", "click a pane to set the cursor")
        else
          div(
            cls := "readout",
            r.map(r =>
              span(
                cls := "readout-layer",
                dataAttr("readout") := "world",
                span(cls := "k", "RAS+"),
                span(cls := "mono", s"${fmt(r.world.x)}, ${fmt(r.world.y)}, ${fmt(r.world.z)}")
              )
            ),
            r.toList.flatMap(_.layers).map { lr =>
              val id = lr.layer.toString
              val name = L.field(id).map(f => Measures.short(f.measure))
                .getOrElse(m.underlays.headOption.map(_.label).getOrElse(id))
              span(
                cls := "readout-layer",
                dataAttr("readout") := id,
                dataAttr("pane") := "volume",
                span(cls := "k", name),
                span(
                  cls := "mono",
                  lr.value match
                    case LayerSampleValue.Scalar(v) => fmt(v)
                    case LayerSampleValue.Label(l) => l.toString
                    case LayerSampleValue.Mask(b) => if b then "in" else "out"
                )
              )
            },
            Option.when(preset != LayoutPreset.Volume)(
              lt.map(t =>
                span(
                  cls := "readout-layer",
                  dataAttr("readout") := "link",
                  span(cls := "k", "surface"),
                  span(cls := "mono", t)
                )
              )
            ).flatten,
            sr.toList.flatMap(_.layerValues).map { (lid, v) =>
              // layer ids are `field@surface`; the label names the hemisphere that surface fills
              val split = SurfacePlacement.splitLayerId(lid.value)
              val fieldId = split.map(_._1).getOrElse(lid.value)
              val hemi = split.map(_._2).flatMap(s =>
                L.placedSurfaces.collectFirst { case (h, d) if d.id == s => h }
              ).getOrElse("")
              span(
                cls := "readout-layer",
                dataAttr("readout") := fieldId,
                dataAttr("pane") := "surface",
                span(
                  cls := "k",
                  L.field(fieldId).map(f => Measures.short(f.measure)).getOrElse(fieldId) +
                    s" · ${hemi.take(1).toUpperCase} vertex"
                ),
                span(cls := "mono", v.toDoubleOption.map(fmt).getOrElse(v))
              )
            }
          )
      }
    )

    val page = div(
      cls := "page workspace-page",
      dataAttr("preset") <-- ws.map(_.layout.preset.toString.toLowerCase),
      headerTag(
        cls := "topbar",
        cls.toggle("readonly") := mode.isInstanceOf[PageMode.Presentation],
        mode match
          case PageMode.Explore(_, _) =>
            a(href := s"/w/${L.workspace}/p/${L.project}", cls := "crumb", L.project)
          case PageMode.Presentation(_, _) => span(cls := "crumb", L.project)
        ,
        span(cls := "crumb muted", "/"),
        span(cls := "crumb mono", L.detail.id),
        span(cls := "crumb muted", "/"),
        span(cls := "crumb", m.title),
        presetSwitch,
        mode match
          case PageMode.Explore(session, _) => Chrome.explore(store, session, savedView, dialog)
          case PageMode.Presentation(shared, saved) => Chrome.presentation(store, shared, saved)
      ),
      mode match
        case PageMode.Presentation(shared, saved) => Chrome.synopsis(L, shared, saved)
        case _ => emptyNode
      ,
      div(cls := "workspace", navigator, centre, inspector),
      status,
      m.warnings.headOption.map(w =>
        div(cls := "callout warn", w.message)
      ),
      child.maybe <-- dialog.signal,
      ws --> onStateChange // subscription owned by the page; released on unmount
    )
    page

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
