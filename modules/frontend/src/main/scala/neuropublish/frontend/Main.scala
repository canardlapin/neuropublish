package neuropublish.frontend

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object Main:
  /** Routes (Stage 2 route scheme): /w/{ws}/p/{project}[/r/{rev}[/view]] */
  private val Project = """/w/([^/]+)/p/([^/]+)/?""".r
  private val Revision = """/w/([^/]+)/p/([^/]+)/r/([^/]+)(?:/view)?/?""".r

  def main(args: Array[String]): Unit =
    val apiBase = dom.window.asInstanceOf[
      scala.scalajs.js.Dynamic
    ].__NP_API.asInstanceOf[scala.scalajs.js.UndefOr[String]]
      .getOrElse(if dom.window.location.port == "5173" then "http://127.0.0.1:8080" else "")
    val api = Api(apiBase)
    val status = Var[String]("loading")
    val content = Var[Option[HtmlElement]](None)

    dom.window.location.pathname match
      case Revision(ws, p, rev) => open(ws, p, Some(rev))
      case Project(ws, p) => open(ws, p, None)
      case _ => status.set("Open a project: /w/{workspace}/p/{project}")

    def open(ws: String, p: String, rev: Option[String]): Unit =
      RevisionPage.load(api, ws, p, rev).onComplete {
        case Success(l) =>
          scala.util.Try(RevisionPage.render(l)) match
            case Success(el) => content.set(Some(el)); status.set("ready")
            case Failure(e) => status.set(s"error: ${e.getMessage}")
        case Failure(e) => status.set(s"error: ${e.getMessage}")
      }

    val app = div(
      idAttr := "np-app",
      dataAttr("status") <-- status.signal,
      child.maybe <-- content.signal,
      child.maybe <-- status.signal.map(s => Option.when(s != "ready")(p(cls := "status", s)))
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), app)
