package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.protocol.json.Manifest
import neuropublish.viewer.ViewUrl
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

object Main:
  /** Route scheme (plan, Stage 2): /w/{ws}/p/{project} and
    * /w/{ws}/p/{project}/r/{rev}[/view][?view-state]
    */
  private val Project = """/w/([^/]+)/p/([^/]+)/?""".r
  private val Revision = """/w/([^/]+)/p/([^/]+)/r/([^/]+)(?:/view)?/?""".r

  def main(args: Array[String]): Unit =
    val apiBase = dom.window.asInstanceOf[js.Dynamic].__NP_API.asInstanceOf[js.UndefOr[String]]
      .getOrElse(if dom.window.location.port == "5173" then "http://127.0.0.1:8080" else "")
    val api = Api(apiBase)
    val status = Var[String]("loading")
    val content = Var[Option[HtmlElement]](None)
    def fail(e: Throwable) = status.set(s"error: ${e.getMessage}")

    dom.window.location.pathname match
      case Revision(ws, p, rev) =>
        Loaded.load(api, ws, p, Some(rev)).onComplete {
          case Success(l) =>
            Try {
              val fromUrl = ViewUrl(dom.window.location.search, l.initialWorkspace)
              val store =
                WorkspaceStore(l, fromUrl) // the host's first model already reflects the URL
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
                }
              )
            } match
              case Success(el) => content.set(Some(el)); status.set("ready")
              case Failure(e) => fail(e)
          case Failure(e) => fail(e)
        }
      case Project(ws, p) =>
        api.project(ws, p).flatMap { s =>
          s.head.fold(scala.concurrent.Future.successful((s, None)))(id =>
            api.revision(id).map(d => (s, d.manifest.as[Manifest].toOption.map(m => (d, m))))
          )
        }.onComplete {
          case Success((s, head)) =>
            content.set(Some(ProjectPage.render(s, head))); status.set("ready")
          case Failure(e) => fail(e)
        }
      case _ => status.set("Open a project: /w/{workspace}/p/{project}")

    val app = div(
      idAttr := "np-app",
      dataAttr("status") <-- status.signal,
      child.maybe <-- content.signal,
      child.maybe <-- status.signal.map(s =>
        Option.when(s != "ready")(
          div(
            cls := "state",
            if s == "loading" then div(cls := "skeleton", "Loading revision…")
            else div(cls := "error", s, " ", a(href := "/w/rotman/p/sherlock", "Back to project"))
          )
        )
      )
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), app)
