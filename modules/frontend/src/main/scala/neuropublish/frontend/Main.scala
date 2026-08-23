package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.api.{RevisionDetail, SavedViewDetail}
import neuropublish.protocol.json.Manifest
import neuropublish.viewer.{ViewUrl, Workspace, WorkspaceState}
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

object Main:
  /** Route scheme (plan, Stages 2 and 4):
    *   - `/login`, `/device`
    *   - `/w/{ws}/p/{project}` overview
    *   - `/w/{ws}/p/{project}/r/{rev}[/view][?view-state]` explore
    *   - `/w/{ws}/p/{project}/v/{viewId}` explore, opened on a saved view's latest version
    *   - `/s/{secret}` presentation (read-only, no account)
    */
  private val Project = """/w/([^/]+)/p/([^/]+)/?""".r
  private val Revision = """/w/([^/]+)/p/([^/]+)/r/([^/]+)(?:/view)?/?""".r
  private val View = """/w/([^/]+)/p/([^/]+)/v/([^/]+)/?""".r
  private val Share = """/s/([^/]+)/?""".r
  private val Login = """/login/?""".r
  private val Device = """/device/?""".r

  def main(args: Array[String]): Unit =
    val apiBase = dom.window.asInstanceOf[js.Dynamic].__NP_API.asInstanceOf[js.UndefOr[String]]
      .getOrElse(if dom.window.location.port == "5173" then "http://127.0.0.1:8080" else "")
    val api = Api(apiBase)
    val session = Session(api)
    val status = Var[String]("loading")
    val content = Var[Option[HtmlElement]](None)
    val path = dom.window.location.pathname

    def here = path + dom.window.location.search
    def toLogin(): Unit =
      dom.window.location.assign(s"/login?next=${js.URIUtils.encodeURIComponent(here)}")

    /** Member routes: a 401 on a private project goes to sign-in; everything else is shown. */
    def fail(e: Throwable): Unit = e match
      case ApiFailure(401, _, _) => toLogin()
      case IngestionFailed(m) => status.set(s"ingestion-failed: $m")
      case _ => status.set(s"error: ${e.getMessage}")

    def show(el: HtmlElement): Unit = { content.set(Some(el)); status.set("ready") }

    /** A revision whose renditions are still being derived is polled every 2 s until it is ready; a
      * failed ingestion is an error (the manifest is there, the canvas is not).
      */
    final case class IngestionFailed(message: String) extends RuntimeException(message)
    def delay(ms: Int): Future[Unit] =
      val p = scala.concurrent.Promise[Unit]()
      dom.window.setTimeout(() => p.success(()), ms)
      p.future
    def awaitIngestion(id: String): Future[RevisionDetail] =
      api.revision(id).flatMap { d =>
        d.ingestion.map(_.status) match
          case Some("pending") | Some("running") =>
            status.set("deriving")
            delay(2000).flatMap(_ => awaitIngestion(id))
          case Some("failed") =>
            Future.failed(IngestionFailed(
              d.ingestion.flatMap(_.error).getOrElse("the browser renditions could not be derived")
            ))
          case _ => Future.successful(d)
      }
    def loadRevision(ws: String, p: String, id: String): Future[Loaded] =
      awaitIngestion(id).flatMap(d => Loaded.fromDetail(ws, p, d, api.rendition))

    /** Explore page over a loaded revision with `initial` already applied to the store. */
    def explore(l: Loaded, initial: Workspace, saved: Option[SavedViewDetail]): Try[HtmlElement] =
      Try {
        val store = WorkspaceStore(l, initial) // the host's first model already reflects the state
        var pending: Option[Int] = None
        WorkspacePage.render(
          store,
          w => {
            // coalesce slider ticks; browsers rate-limit replaceState
            pending.foreach(dom.window.clearTimeout)
            pending = Some(dom.window.setTimeout(
              () => {
                pending = None
                dom.window.history.replaceState(
                  null,
                  "",
                  s"${dom.window.location.pathname}?${ViewUrl.encode(w)}"
                )
              },
              250
            ))
          },
          PageMode.Explore(session, saved)
        )
      }

    path match
      case Login() =>
        session.refresh()
        show(AuthPages.login(session, AuthPages.safeNext(AuthPages.query("next"))))
      case Device() =>
        session.refresh().foreach {
          case Some(_) => show(AuthPages.device(session, AuthPages.query("code")))
          case None => toLogin()
        }
      case Share(secret) =>
        api.openShare(secret).flatMap { shared =>
          val rev = shared.revision
          Future.fromTry(WorkspaceState.decode(shared.version.state).left.map(m =>
            RuntimeException(s"This saved view cannot be read by this viewer: $m")
          ).toTry).flatMap(saved =>
            Loaded.fromDetail(
              rev.workspace,
              rev.project,
              rev,
              r => api.shareRendition(secret, r.assetId)
            ).map(l => (shared, saved, l))
          )
        }.onComplete {
          case Success((shared, saved, l)) =>
            Try {
              val state = WorkspaceState.apply(saved, l.initialWorkspace)
              val store = WorkspaceStore(l, state)
              WorkspacePage.render(store, _ => (), PageMode.Presentation(shared, state))
            } match
              case Success(el) => show(el)
              case Failure(e) => status.set(s"error: ${e.getMessage}")
          case Failure(ApiFailure(410, _, _)) => status.set("revoked")
          case Failure(ApiFailure(404, _, _)) => status.set("revoked")
          case Failure(e) => status.set(s"error: ${e.getMessage}")
        }
      case View(ws, p, viewId) =>
        session.refresh()
        api.getView(viewId).flatMap { v =>
          val latest = v.versions.find(_.version == v.latest).orElse(v.versions.lastOption)
            .getOrElse(throw RuntimeException("This saved view has no versions."))
          val saved = WorkspaceState.decode(latest.state).fold(
            m => throw RuntimeException(s"This saved view cannot be read by this viewer: $m"),
            identity
          )
          loadRevision(ws, p, v.revision).map(l => (v, saved, l))
        }.onComplete {
          case Success((v, saved, l)) =>
            // same path as the URL: saved state applied onto the revision's recommendations
            explore(l, WorkspaceState.apply(saved, l.initialWorkspace), Some(v)) match
              case Success(el) => show(el)
              case Failure(e) => fail(e)
          case Failure(e) => fail(e)
        }
      case Revision(ws, p, rev) =>
        session.refresh()
        loadRevision(ws, p, rev).onComplete {
          case Success(l) =>
            explore(l, ViewUrl(dom.window.location.search, l.initialWorkspace), None) match
              case Success(el) => show(el)
              case Failure(e) => fail(e)
          case Failure(e) => fail(e)
        }
      case Project(ws, p) =>
        session.refresh()
        api.project(ws, p).flatMap { s =>
          s.head.fold(Future.successful((s, None)))(id =>
            api.revision(id).map(d => (s, d.manifest.as[Manifest].toOption.map(m => (d, m))))
          )
        }.onComplete {
          case Success((s, head)) =>
            show(ProjectPage.render(
              s,
              head,
              session.account,
              id => api.revision(id).map(_.ingestion)
            ))
          case Failure(e) => fail(e)
        }
      case _ =>
        session.refresh()
        status.set("Open a project: /w/{workspace}/p/{project}")

    val app = div(
      idAttr := "np-app",
      dataAttr("status") <-- status.signal,
      child.maybe <-- content.signal,
      child.maybe <-- status.signal.map(s =>
        Option.when(s != "ready")(
          div(
            cls := "state",
            s match
              case "loading" => div(cls := "skeleton", "Loading revision…")
              case "deriving" =>
                div(
                  cls := "skeleton",
                  dataAttr("testid") := "ingestion-pending",
                  "Deriving browser renditions…",
                  p(
                    cls := "muted",
                    "The revision is committed; its volumes are being prepared for the browser. This page refreshes every 2 seconds."
                  )
                )
              case m if m.startsWith("ingestion-failed: ") =>
                div(
                  cls := "error",
                  dataAttr("testid") := "ingestion-failed",
                  h1("Ingestion failed"),
                  p(m.stripPrefix("ingestion-failed: ")),
                  p(
                    cls := "muted",
                    "The revision is committed but its browser renditions could not be derived, so there is nothing to draw. Re-run ingestion or push a corrected revision."
                  ),
                  a(href := AuthPages.DefaultNext, "Back to project")
                )
              case "revoked" =>
                div(
                  cls := "revoked",
                  dataAttr("testid") := "link-revoked",
                  h1("This link was revoked or has expired."),
                  p(
                    cls := "muted",
                    "Read-only links point at one saved-view version and can be withdrawn by the project at any time. Ask the person who shared it for a new link."
                  )
                )
              case _ =>
                div(cls := "error", s, " ", a(href := AuthPages.DefaultNext, "Back to project"))
          )
        )
      )
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), app)
