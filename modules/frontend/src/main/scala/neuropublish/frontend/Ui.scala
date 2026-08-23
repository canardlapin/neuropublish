package neuropublish.frontend

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import io.circe.Json
import org.scalajs.dom
import scala.scalajs.js

/** Small shared chrome: modal dialog, date formatting, download links. Tokens from np.css. */
object Ui:
  val download: HtmlAttr[String] = htmlAttr("download", StringAsIsCodec)

  /** Modal over the page. Escape or the backdrop closes it; focus lands on the first field. */
  def dialog(title: String, testId: String, onClose: () => Unit)(body: Modifier[HtmlElement]*)
      : HtmlElement =
    div(
      cls := "np-dialog-backdrop",
      onClick.filter(e => e.target == e.currentTarget) --> (_ => onClose()),
      onKeyDown.filter(_.key == "Escape") --> (_ => onClose()),
      div(
        cls := "np-dialog",
        role := "dialog",
        htmlAttr("aria-modal", StringAsIsCodec) := "true",
        aria.label := title,
        dataAttr("testid") := testId,
        div(
          cls := "np-dialog-head",
          h3(title),
          button(cls := "ghost", aria.label := "close", "×", onClick --> (_ => onClose()))
        ),
        div(cls := "np-dialog-body", body),
        onMountCallback(c =>
          Option(c.thisNode.ref.querySelector("input, select, button:not([aria-label=close])"))
            .foreach(_.asInstanceOf[dom.HTMLElement].focus())
        )
      )
    )

  /** ISO instant → `2026-08-22 14:03`; never guesses when the string is not an instant. */
  def date(iso: String): String =
    if iso.length >= 16 && iso.charAt(10) == 'T' then iso.take(16).replace("T", " ") else iso
  def day(iso: String): String = if iso.length >= 10 then iso.take(10) else iso

  /** A `data:` URL for a JSON payload (no server round trip; nothing executable). */
  def jsonDataUrl(j: Json): String =
    "data:application/json;charset=utf-8," + js.URIUtils.encodeURIComponent(j.spaces2)

  /** Human form of a facet value: strings bare, everything else compact JSON. */
  def jsonValue(j: Json): String = j.asString.getOrElse(j.noSpaces)

  def copyToClipboard(text: String): Unit =
    val _ = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard.writeText(text)
