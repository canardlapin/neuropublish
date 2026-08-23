package neuropublish.frontend

import com.raquo.laminar.api.L.*
import neuropublish.viewer.SafeNext
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

/** `/login` and `/device`: the browser side of the identity boundary (alpha local provider). */
object AuthPages:
  val DefaultNext = "/w/rotman/p/sherlock"

  /** Only same-origin paths are honoured as `next` (no open redirect): the pure [[SafeNext]] rule,
    * then the browser's own parse must land on this origin.
    */
  def safeNext(raw: Option[String]): String =
    val origin = dom.window.location.origin
    raw.flatMap(SafeNext.accept(_, origin)).filter(n =>
      Try(new dom.URL(n, origin).origin == origin).getOrElse(false)
    ).getOrElse(DefaultNext)

  def login(session: Session, next: String): HtmlElement =
    val email = Var("")
    val password = Var("")
    val error = Var[Option[String]](None)
    val busy = Var(false)
    def submit(): Unit =
      if !busy.now() then
        busy.set(true); error.set(None)
        session.api.login(email.now().trim, password.now()).onComplete {
          case Success(me) => session.me.set(Some(me)); dom.window.location.assign(next)
          case Failure(e) => busy.set(false); error.set(Some(e.getMessage))
        }
    div(
      cls := "page auth-page",
      form(
        cls := "auth-form",
        dataAttr("testid") := "login-form",
        onSubmit.preventDefault --> (_ => submit()),
        h1("Sign in"),
        p(cls := "muted", "Neuropublish alpha uses a local identity provider."),
        label(
          cls := "field stacked",
          span("email"),
          input(
            typ := "email",
            nameAttr := "email",
            autoComplete := "username",
            required := true,
            controlled(value <-- email.signal, onInput.mapToValue --> email.writer)
          )
        ),
        label(
          cls := "field stacked",
          span("password"),
          input(
            typ := "password",
            nameAttr := "password",
            autoComplete := "current-password",
            required := true,
            controlled(value <-- password.signal, onInput.mapToValue --> password.writer)
          )
        ),
        child.maybe <-- error.signal.map(_.map(m => div(cls := "error", role := "alert", m))),
        div(
          cls := "row",
          button(typ := "submit", cls := "primary", disabled <-- busy.signal, "Sign in")
        )
      )
    )

  /** Device-code approval: what a headless `npub login` tells the user to open. */
  def device(session: Session, prefill: Option[String]): HtmlElement =
    val code = Var(prefill.getOrElse(""))
    val state = Var[Either[String, Boolean]](Right(false)) // Left error | Right approved
    val busy = Var(false)
    def approve(): Unit =
      val c = code.now().trim.toUpperCase
      if c.nonEmpty && !busy.now() then
        busy.set(true)
        session.api.deviceApprove(c).onComplete {
          case Success(_) => busy.set(false); state.set(Right(true))
          case Failure(e) => busy.set(false); state.set(Left(e.getMessage))
        }
    div(
      cls := "page auth-page",
      dataAttr("device-status") <-- state.signal.map {
        case Right(true) => "approved"
        case Right(false) => "pending"
        case Left(_) => "error"
      },
      form(
        cls := "auth-form",
        dataAttr("testid") := "device-form",
        onSubmit.preventDefault --> (_ => approve()),
        h1("Approve a command-line sign-in"),
        child <-- session.me.signal.map {
          case Some(m) => p(cls := "muted", s"Signed in as ${m.user.name} (${m.user.email}).")
          case None => p(cls := "muted", "Sign in first, then enter the code.")
        },
        child <-- state.signal.map {
          case Right(true) =>
            div(
              cls := "callout ok",
              dataAttr("testid") := "device-approved",
              strong("Approved"),
              p("Return to your terminal; ", span(cls := "mono", "npub login"), " will continue.")
            )
          case other =>
            div(
              label(
                cls := "field stacked",
                span("user code (from the terminal)"),
                input(
                  typ := "text",
                  dataAttr("testid") := "device-code",
                  placeholder := "XXXX-XXXX",
                  autoComplete := "off",
                  spellCheck := false,
                  cls := "mono code-input",
                  controlled(value <-- code.signal, onInput.mapToValue --> code.writer)
                )
              ),
              other.left.toOption.map(m => div(cls := "error", role := "alert", m)),
              div(
                cls := "row",
                button(
                  typ := "submit",
                  cls := "primary",
                  dataAttr("testid") := "device-approve",
                  disabled <-- busy.signal.combineWith(code.signal).map((b, c) =>
                    b || c.trim.isEmpty
                  ),
                  "Approve"
                )
              )
            )
        }
      )
    )

  def query(name: String): Option[String] =
    val sp = new dom.URLSearchParams(dom.window.location.search)
    Option(sp.get(name)).filter(_ != null).map(_.toString).filter(_.nonEmpty)
