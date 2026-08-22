package neuropublish.frontend

import com.raquo.laminar.api.L.*
import org.scalajs.dom

object Main:
  def main(args: Array[String]): Unit =
    val app = div(
      fontFamily := "system-ui, sans-serif",
      padding := "2rem",
      h1("Neuropublish"),
      p("Stage 0 shell. Nothing is published yet.")
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), app)
