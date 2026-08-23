package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.api.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/** Provenance inspector over the server's read model (`GET revisions/{rev}/provenance`): a readable
  * pipeline in edge order, a node-detail panel, and the compatibility section. A facet whose inputs
  * differ is always shown as its groups ("22 × AR(2)", "4 × AR(1)"), never as one value (product
  * definition, "Analysis and provenance inspectors"). Activities the server does not understand are
  * retained, downloadable, and carry no action.
  */
object ProvenancePanel:
  private def understood(n: ProvenanceNode) = n.interpretation == "understood"

  /** Nodes in pipeline order: every edge contributes `from` then `to`; unreferenced nodes follow.
    */
  private def ordered(p: Provenance): List[ProvenanceNode] =
    val byId = p.nodes.map(n => n.id -> n).toMap
    val walk = p.edges.flatMap(e => List(e.from, e.to)).distinct.flatMap(byId.get)
    walk ++ p.nodes.filterNot(n => walk.exists(_.id == n.id))

  def render(prov: Future[Provenance]): HtmlElement =
    val loaded = Var[Option[Either[String, Provenance]]](None)
    prov.onComplete {
      case Success(p) => loaded.set(Some(Right(p)))
      case Failure(e) => loaded.set(Some(Left(e.getMessage)))
    }
    div(
      cls := "facts-panel provenance-panel",
      child <-- loaded.signal.map {
        case None => div(cls := "muted", "Loading provenance…")
        case Some(Left(m)) => div(cls := "callout warn", "Provenance could not be loaded: ", m)
        case Some(Right(p)) => body(p)
      }
    )

  /** Presentation route: the read model is members-only in this stage; say so, show nothing false.
    */
  def unavailable(L: Loaded): HtmlElement =
    div(
      cls := "facts-panel provenance-panel",
      p(
        cls := "muted",
        "Full provenance (operation graph, receipts, input compatibility) is available to project members. This link shows the published result and its presentation."
      ),
      L.manifest.warnings.map(w =>
        div(cls := "callout warn", w.message)
      )
    )

  private def body(p: Provenance): HtmlElement =
    val selected = Var[Option[ProvenanceNode]](None)
    val nodes = ordered(p)
    div(
      cls := "prov-body",
      // ---- receipts summary ----
      div(
        cls := "group",
        div(cls := "k", "Inputs"),
        div(
          cls := "fact",
          span(cls := "k", "receipts"),
          span(
            s"${p.receiptCount}",
            p.receiptSchema.map(s => span(cls := "muted mono small", s" · $s"))
          )
        )
      ),
      // ---- compatibility: never a single value for a non-shared facet ----
      div(
        cls := "group",
        div(cls := "k", "Input compatibility"),
        if p.facets.isEmpty then div(cls := "muted small", "No facets compared.") else emptyNode,
        p.facets.map(facet)
      ),
      // ---- readable pipeline ----
      div(
        cls := "group",
        div(cls := "k", "Pipeline"),
        div(
          cls := "pipeline",
          nodes.map(n =>
            div(
              cls := "prov-node",
              cls := n.kind,
              cls("unknown") := !understood(n),
              cls("selected") <-- selected.signal.map(_.exists(_.id == n.id)),
              dataAttr("testid") := "prov-node",
              dataAttr("node") := n.id,
              dataAttr("kind") := n.kind,
              dataAttr("interpretation") := n.interpretation,
              role := "button",
              tabIndex := 0,
              onClick --> (_ => selected.set(Some(n))),
              onKeyDown.filter(e => e.key == "Enter" || e.key == " ").preventDefault -->
                (_ => selected.set(Some(n))),
              div(
                cls := "prov-node-head",
                span(cls := "prov-label", n.label),
                span(cls := "pill", n.kind),
                if understood(n) then emptyNode
                else span(cls := "pill warn static", "retained, not interpreted"),
                if n.hosted.contains(false) then span(cls := "pill", "not hosted") else emptyNode
              ),
              n.schemaId.map(s =>
                div(cls := "mono muted small", s + n.schemaVersion.map(v => s" @ $v").getOrElse(""))
              ),
              if understood(n) then emptyNode
              else
                div(
                  cls := "small",
                  a(
                    href := Ui.jsonDataUrl(n.payload),
                    Ui.download := s"${n.id}.json",
                    "download payload JSON"
                  )
                )
            )
          )
        )
      ),
      // ---- node detail ----
      child.maybe <-- selected.signal.map(_.map(detail)),
      // ---- warnings from the read model ----
      p.warnings.map(w =>
        div(
          cls := "callout warn",
          w.hcursor.downField("message").as[String].getOrElse(Ui.jsonValue(w))
        )
      )
    )

  private def facet(f: CompatibilityFacet): HtmlElement =
    val total = f.groups.map(_.count).sum
    div(
      cls := "prov-facet",
      cls("differs") := !f.shared,
      dataAttr("testid") := "prov-facet",
      dataAttr("facet") := f.facet,
      dataAttr("shared") := f.shared.toString,
      if f.shared then
        // exactly one group by construction; still never invent one if the server sent none
        f.groups.headOption.fold(
          div(cls := "fact", span(cls := "k mono", f.facet), span(cls := "muted", "(no values)"))
        )(g =>
          div(
            cls := "fact",
            span(cls := "k mono", f.facet),
            span(span(cls := "mono", Ui.jsonValue(g.value)), span(cls := "muted", s" · all $total"))
          )
        )
      else
        div(
          cls := "facet-differs",
          div(
            cls := "facet-head",
            span(cls := "pill warn static", "Inputs differ"),
            span("Inputs differ on ", span(cls := "mono", f.facet))
          ),
          f.groups.sortBy(-_.count).map(g =>
            detailsTag(
              cls := "prov-group",
              dataAttr("testid") := "prov-group",
              summaryTag(
                span(cls := "mono", s"${g.count} × ${Ui.jsonValue(g.value)}"),
                span(cls := "muted small", s" · ${g.members.length} members")
              ),
              div(cls := "members mono small", g.members.mkString(", "))
            )
          )
        )
    )

  private def detail(n: ProvenanceNode): HtmlElement =
    div(
      cls := "group prov-detail",
      dataAttr("testid") := "prov-detail",
      div(cls := "k", "Node"),
      div(cls := "fact", span(cls := "k", "id"), span(cls := "mono", n.id)),
      div(cls := "fact", span(cls := "k", "label"), span(n.label)),
      div(cls := "fact", span(cls := "k", "kind"), span(n.kind)),
      div(
        cls := "fact",
        span(cls := "k", "schema"),
        span(cls := "mono", n.schemaId.getOrElse("(none)"), n.schemaVersion.map(v => s" @ $v"))
      ),
      div(cls := "fact", span(cls := "k", "interpretation"), span(n.interpretation)),
      div(
        cls := "fact",
        span(cls := "k", "hosted"),
        span(n.hosted.map(h => if h then "yes" else "no").getOrElse("(not stated)"))
      ),
      div(cls := "k", "Payload"),
      pre(cls := "payload mono small", n.payload.spaces2)
    )
