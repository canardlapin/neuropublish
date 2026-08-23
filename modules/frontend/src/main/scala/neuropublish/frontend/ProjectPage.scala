package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.api.*
import neuropublish.protocol.json.Manifest

/** Project overview: question, current revision, analyses, what changed, warnings (product
  * definition).
  */
object ProjectPage:
  def render(
      summary: ProjectSummary,
      head: Option[(RevisionDetail, Manifest)],
      account: HtmlElement
  ): HtmlElement =
    val base = s"/w/${summary.workspace}/p/${summary.project}"
    div(
      cls := "page project",
      headerTag(
        cls := "topbar",
        span(cls := "crumb muted", summary.workspace),
        span(cls := "crumb muted", "/"),
        span(cls := "crumb", summary.project),
        div(cls := "spacer"),
        account
      ),
      headerTag(
        cls := "page-header",
        h1(head.map(_._2.title).getOrElse(summary.project)),
        head.flatMap(_._2.synopsis).map(s => p(cls := "synopsis", s))
      ),
      head.map { (d, m) =>
        div(
          cls := "facts",
          span(
            s"${m.analyses.length} ${if m.analyses.length == 1 then "analysis" else "analyses"}"
          ),
          span(cls := "dot", "·"),
          span(s"${m.analyses.map(_.estimands.length).sum} estimands"),
          span(cls := "dot", "·"),
          m.analyses.flatMap(_.sampleSize).headOption.map(n => span(s"n = $n")),
          span(cls := "dot", "·"),
          span(s"${m.resultFields.length} result fields")
        )
      },
      head.map { (d, m) =>
        m.warnings.headOption.map(w =>
          div(
            cls := "callout warn",
            strong("Interpretation warning"),
            p(w.message)
          )
        )
      },
      sectionTag(
        cls := "panel",
        div(
          cls := "panel-head",
          h2("Revisions"),
          a(
            cls := "btn primary",
            href := s"$base/r/${summary.head.getOrElse("")}/view",
            "Open current revision"
          )
        ),
        summary.revisions.reverse.map { r =>
          div(
            cls := "row revision-row",
            a(cls := "mono", href := s"$base/r/${r.id}/view", r.id),
            span(r.message.getOrElse("")),
            span(cls := "muted", r.committedAt.take(19).replace("T", " ")),
            if summary.head.contains(r.id) then span(cls := "pill accent", "current") else emptyNode
          )
        }
      )
    )
