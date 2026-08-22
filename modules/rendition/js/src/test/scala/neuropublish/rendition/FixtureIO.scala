package neuropublish.rendition

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.*

@js.native
@JSImport("node:fs", JSImport.Namespace)
private object Fs extends js.Object:
  def readFileSync(path: String): Uint8Array = js.native
  def readFileSync(path: String, encoding: String): String = js.native
  def existsSync(path: String): Boolean = js.native

object FixtureIO:
  val root: String =
    List(
      "modules/conformance/fixtures",
      "../../conformance/fixtures",
      "../../../modules/conformance/fixtures"
    )
      .find(Fs.existsSync).getOrElse(throw IllegalStateException("fixtures not found"))
  def readBytes(rel: String): Array[Byte] =
    val u = Fs.readFileSync(s"$root/$rel"); val out = new Array[Byte](u.length)
    var i = 0
    while i < out.length do { out(i) = u(i).toByte; i += 1 }
    out
  def readText(rel: String): String = Fs.readFileSync(s"$root/$rel", "utf8")
  def exists(rel: String): Boolean = Fs.existsSync(s"$root/$rel")
