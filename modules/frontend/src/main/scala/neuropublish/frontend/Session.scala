package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.api.*
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

/** The signed-in principal for this browser (session cookie), or none. The shell reads it for the
  * topbar; pages read it to decide what they may offer.
  */
final class Session(val api: Api)(using ExecutionContext):
  val me: Var[Option[Me]] = Var(None)

  /** Ask the server who we are; a 401 simply means signed out. */
  def refresh(): Future[Option[Me]] =
    api.me().map(Some(_)).recover { case _ => None }.map { m => me.set(m); m }

  def signOut(): Unit =
    api.logout().onComplete { _ =>
      me.set(None)
      dom.window.location.assign("/login")
    }

  /** `/login?next=…` for the current location. */
  def loginHref: String =
    s"/login?next=${js.URIUtils.encodeURIComponent(dom.window.location.pathname +
        dom.window.location.search)}"

  /** Topbar account element: name/email + Sign out, or a Sign in link. */
  def account: HtmlElement =
    div(
      cls := "account",
      dataAttr("testid") := "account",
      child <-- me.signal.map {
        case Some(m) =>
          span(
            cls := "account-user",
            span(m.user.name),
            span(cls := "muted small", m.user.email),
            button(cls := "ghost small-btn", "Sign out", onClick --> (_ => signOut()))
          )
        case None => a(cls := "btn ghost", href := loginHref, "Sign in")
      }
    )

/** How a workspace page is being used: by a member who may save and share, or by a link viewer. */
enum PageMode:
  /** Explore route. `saved` is the open saved view (if the page was reached through `/v/{id}`). */
  case Explore(session: Session, saved: Option[SavedViewDetail])

  /** Presentation route: read-only, no account; `savedState` is the shared version's workspace. */
  case Presentation(shared: SharedView, savedState: neuropublish.viewer.Workspace)
