package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.api.*
import neuropublish.protocol.Measures
import neuropublish.viewer.{Workspace, WorkspaceState}
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** Workspace topbar chrome per page mode: saved-view and share flows for members, the read-only bar
  * and "Return to saved view" for link viewers. Dialogs are rendered into `dialog`.
  */
object Chrome:
  private def viewHref(L: Loaded, id: String) = s"/w/${L.workspace}/p/${L.project}/v/$id"

  /** Explore-route topbar actions: Views · Save view · Share · Reset view · account. */
  def explore(
      store: WorkspaceStore,
      session: Session,
      saved: Var[Option[SavedViewDetail]],
      dialog: Var[Option[HtmlElement]]
  ): Seq[Modifier[HtmlElement]] =
    val L = store.loaded
    val api = session.api
    val notice = Var[Option[String]](None)
    def close() = dialog.set(None)

    def currentState = WorkspaceState.encode(store.state.now())

    // ---- Save view: name → new view; or append a version to the open view ----
    def saveDialog(): HtmlElement =
      val name = Var(saved.now().map(_.name).getOrElse(""))
      val asNew = Var(saved.now().isEmpty)
      val err = Var[Option[String]](None)
      val busy = Var(false)
      def submit(): Unit =
        val n = name.now().trim
        if busy.now() then ()
        else if asNew.now() && n.isEmpty then err.set(Some("A view needs a name."))
        else
          busy.set(true); err.set(None)
          val call = saved.now() match
            case Some(v) if !asNew.now() => api.updateView(v.id, currentState)
            case _ => api.saveView(L.detail.id, n, currentState)
          call.onComplete {
            case Success(v) =>
              saved.now() match
                case Some(prev) if prev.id == v.id =>
                  saved.set(Some(v)); close(); notice.set(Some(s"Saved version ${v.latest}"))
                case _ => dom.window.location.assign(viewHref(L, v.id)) // open the new view
            case Failure(e) => busy.set(false); err.set(Some(e.getMessage))
          }
      Ui.dialog("Save view", "save-dialog", close)(
        form(
          onSubmit.preventDefault --> (_ => submit()),
          saved.now().map(v =>
            label(
              cls := "field stacked",
              span("how"),
              select(
                controlled(
                  value <-- asNew.signal.map(if _ then "new" else "version"),
                  onChange.mapToValue --> (v => asNew.set(v == "new"))
                ),
                option(value := "version", s"New version of “${v.name}” (now v${v.latest})"),
                option(value := "new", "New view")
              )
            )
          ),
          label(
            cls := "field stacked",
            span("name"),
            input(
              typ := "text",
              dataAttr("testid") := "view-name",
              required <-- asNew.signal,
              disabled <-- asNew.signal.map(!_),
              placeholder := "e.g. Left STG peak, t > 4.5",
              controlled(value <-- name.signal, onInput.mapToValue --> name.writer)
            )
          ),
          p(
            cls := "muted small",
            "Saves the current layer order, display, threshold, cursor, layout, and inspector. The revision and its digest are not changed."
          ),
          child.maybe <-- err.signal.map(_.map(m => div(cls := "error", role := "alert", m))),
          div(
            cls := "row end",
            button(typ := "button", cls := "ghost", "Cancel", onClick --> (_ => close())),
            button(
              typ := "submit",
              cls := "primary",
              dataAttr("testid") := "save-view-confirm",
              disabled <-- busy.signal,
              "Save"
            )
          )
        )
      )

    // ---- Views list for this revision ----
    def viewsDialog(): HtmlElement =
      val list = Var[Option[Either[String, List[SavedViewSummary]]]](None)
      api.listViews(L.detail.id).onComplete {
        case Success(l) => list.set(Some(Right(l)))
        case Failure(e) => list.set(Some(Left(e.getMessage)))
      }
      Ui.dialog("Saved views", "views-dialog", close)(
        p(cls := "muted small", "Views saved on revision ", span(cls := "mono", L.detail.id), "."),
        child <-- list.signal.map {
          case None => div(cls := "muted", "Loading…")
          case Some(Left(m)) => div(cls := "error", m)
          case Some(Right(Nil)) => div(cls := "muted", "No saved views yet.")
          case Some(Right(vs)) =>
            div(
              cls := "list",
              vs.map(v =>
                a(
                  cls := "row list-row",
                  dataAttr("testid") := "view-row",
                  href := viewHref(L, v.id),
                  span(cls := "grow", v.name),
                  span(cls := "muted small", s"v${v.latest} · ${v.owner}"),
                  span(cls := "muted small", Ui.date(v.updatedAt))
                )
              )
            )
        }
      )

    // ---- Share: from a saved view, one immutable version ----
    def shareDialog(v: SavedViewDetail): HtmlElement =
      val expiry = Var("30")
      val created = Var[Option[ShareLinkCreated]](None)
      val links = Var[Option[Either[String, List[ShareLinkSummary]]]](None)
      val err = Var[Option[String]](None)
      val busy = Var(false)
      val copied = Var(false)
      def reload(): Unit = api.listShareLinks(L.workspace, L.project).onComplete {
        case Success(l) => links.set(Some(Right(l.filter(_.revokedAt.isEmpty))))
        case Failure(e) => links.set(Some(Left(e.getMessage)))
      }
      reload()
      def create(): Unit =
        if !busy.now() then
          busy.set(true); err.set(None)
          api.createShareLink(v.id, v.latest, expiry.now().toIntOption).onComplete {
            case Success(c) => busy.set(false); created.set(Some(c)); reload()
            case Failure(e) => busy.set(false); err.set(Some(e.getMessage))
          }
      def revoke(id: String): Unit = api.revokeShareLink(id).onComplete {
        case Success(_) =>
          if created.now().exists(_.id == id) then created.set(None)
          reload()
        case Failure(e) => err.set(Some(e.getMessage))
      }
      def linkUrl(c: ShareLinkCreated) =
        if c.url.nonEmpty then c.url else s"${dom.window.location.origin}/s/${c.secret}"
      Ui.dialog("Share a read-only link", "share-dialog", close)(
        p(
          "Shares “",
          span(v.name),
          "” at version ",
          span(cls := "mono", s"v${v.latest}"),
          " of revision ",
          span(cls := "mono", L.detail.id),
          "."
        ),
        p(
          cls := "policy small",
          "Anyone with the link can view this version; revocable at any time."
        ),
        child <-- created.signal.map {
          case Some(c) =>
            div(
              cls := "group",
              div(cls := "k", "Your link (shown once)"),
              div(
                cls := "row",
                input(
                  typ := "text",
                  readOnly := true,
                  cls := "grow mono",
                  dataAttr("testid") := "share-link",
                  value := linkUrl(c),
                  onClick --> (e => e.target.asInstanceOf[dom.HTMLInputElement].select())
                ),
                button(
                  typ := "button",
                  dataAttr("testid") := "copy-link",
                  child.text <-- copied.signal.map(if _ then "Copied" else "Copy"),
                  onClick --> { _ =>
                    Ui.copyToClipboard(linkUrl(c)); copied.set(true)
                    dom.window.setTimeout(() => copied.set(false), 1500): Unit
                  }
                )
              ),
              div(
                cls := "muted small",
                c.expiresAt.map(e => s"Expires ${Ui.day(e)}.").getOrElse("Does not expire.")
              )
            )
          case None =>
            form(
              cls := "group",
              onSubmit.preventDefault --> (_ => create()),
              label(
                cls := "field",
                span("expires"),
                select(
                  dataAttr("testid") := "share-expiry",
                  controlled(value <-- expiry.signal, onChange.mapToValue --> expiry.writer),
                  option(value := "30", "in 30 days"),
                  option(value := "90", "in 90 days"),
                  option(value := "never", "never")
                )
              ),
              div(
                cls := "row end",
                button(
                  typ := "submit",
                  cls := "primary",
                  dataAttr("testid") := "create-link",
                  disabled <-- busy.signal,
                  "Create link"
                )
              )
            )
        },
        child.maybe <-- err.signal.map(_.map(m => div(cls := "error", role := "alert", m))),
        div(
          cls := "group",
          div(cls := "k", "Active links for this project"),
          child <-- links.signal.map {
            case None => div(cls := "muted small", "Loading…")
            case Some(Left(m)) => div(cls := "error", m)
            case Some(Right(Nil)) => div(cls := "muted small", "No active links.")
            case Some(Right(ls)) =>
              div(
                cls := "list",
                ls.map(l =>
                  div(
                    cls := "row list-row",
                    dataAttr("testid") := "share-link-row",
                    span(
                      cls := "grow",
                      span(cls := "mono", if l.view == v.id then v.name else l.view),
                      span(cls := "muted small", s" v${l.version}")
                    ),
                    span(
                      cls := "muted small",
                      l.expiresAt.map(e => s"expires ${Ui.day(e)}").getOrElse("no expiry")
                    ),
                    span(cls := "muted small", s"by ${l.createdBy}"),
                    button(
                      typ := "button",
                      cls := "ghost danger",
                      dataAttr("testid") := "revoke-link",
                      "Revoke",
                      onClick --> (_ => revoke(l.id))
                    )
                  )
                )
              )
          }
        )
      )

    Seq(
      child.maybe <-- saved.signal.map(_.map(v =>
        span(cls := "pill accent", title := "saved view", s"${v.name} · v${v.latest}")
      )),
      child.maybe <-- notice.signal.map(_.map(n => span(cls := "pill ok", n))),
      div(cls := "spacer"),
      button(
        cls := "ghost",
        dataAttr("testid") := "views-list",
        "Views",
        onClick --> (_ => dialog.set(Some(viewsDialog())))
      ),
      button(
        cls := "ghost",
        dataAttr("testid") := "save-view",
        child.text <-- saved.signal.map(s => if s.isDefined then "Save" else "Save view"),
        onClick --> (_ => dialog.set(Some(saveDialog())))
      ),
      child.maybe <-- saved.signal.map(_.map(v =>
        button(
          cls := "ghost",
          dataAttr("testid") := "share-view",
          "Share",
          onClick --> (_ => dialog.set(Some(shareDialog(v))))
        )
      )),
      button(
        cls := "ghost",
        "Reset view",
        onClick --> (_ => store.dispatch(Workspace.Action.ResetAll))
      ),
      session.account
    )

  /** Presentation-route topbar: read-only pill, return-to-saved, no account, no save/share. */
  def presentation(
      store: WorkspaceStore,
      shared: SharedView,
      savedState: Workspace
  ): Seq[Modifier[HtmlElement]] =
    Seq(
      div(cls := "spacer"),
      span(
        cls := "pill readonly-bar",
        dataAttr("testid") := "readonly-bar",
        "read-only link · ",
        shared.expiresAt.map(e => s"expires ${Ui.day(e)}").getOrElse("no expiry")
      ),
      button(
        cls := "ghost",
        dataAttr("testid") := "return-to-saved",
        title := "discard local changes and show the shared version again",
        "Return to saved view",
        onClick --> (_ => store.replace(savedState))
      )
    )

  /** "What you are looking at": a short synopsis from the manifest and the shared version. */
  def synopsis(L: Loaded, shared: SharedView, savedState: Workspace): HtmlElement =
    val m = L.manifest
    val visible = savedState.layers.filter(_.current.visible).flatMap(l =>
      L.volumeFields.find(_.id == l.id).map(f =>
        val est = m.analyses.flatMap(_.estimands).find(_.id == f.estimand).map(_.label)
        est.fold(Measures.label(f.measure))(e => s"$e · ${Measures.label(f.measure)}")
      )
    )
    div(
      cls := "synopsis-bar",
      dataAttr("testid") := "share-synopsis",
      div(cls := "k", "What you are looking at"),
      div(
        strong(shared.view.name),
        span(
          cls := "muted",
          s" — saved view v${shared.version.version}, ${Ui.date(shared.version.savedAt)} by ${shared.version.savedBy}"
        )
      ),
      div(m.title, m.synopsis.map(s => span(cls := "muted", s" — $s"))),
      div(
        cls := "muted small",
        m.analyses.map(a => a.label + a.sampleSize.map(n => s" (n = $n)").getOrElse("")).mkString(
          "; "
        ),
        if visible.nonEmpty then s" · showing ${visible.mkString(", ")}" else " · no layer visible",
        savedState.cursor.map((x, y, z) => f" · cursor $x%.0f, $y%.0f, $z%.0f (RAS+)")
      ),
      div(
        cls := "muted small",
        "Controls change only what you see here; the saved view and the published revision are not modified."
      )
    )
