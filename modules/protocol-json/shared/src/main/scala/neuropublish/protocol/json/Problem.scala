package neuropublish.protocol.json

import io.circe.DecodingFailure

/** One admission problem, addressed by a JSON Pointer (RFC 6901) into the manifest. `""` is the
  * whole document; a pointer may name a location that does not exist yet (a missing required member
  * is reported at the member's pointer).
  */
final case class Problem(pointer: String, message: String):
  def render: String = if pointer.isEmpty then message else s"$pointer: $message"
  override def toString: String = render

object Problem:
  def render(problems: List[Problem]): String = problems.map(_.render).mkString("; ")

/** JSON Pointer construction per RFC 6901. */
object JsonPointer:
  def escape(token: String): String = token.replace("~", "~0").replace("/", "~1")
  def of(tokens: Seq[String]): String = tokens.map(t => "/" + escape(t)).mkString
  def field(parent: String, name: String): String = parent + "/" + escape(name)
  def index(parent: String, i: Int): String = parent + "/" + i

  /** Pointer to the cursor position a circe decoding failure refers to (from its `.a[0].b` path).
    */
  def ofFailure(f: DecodingFailure): String =
    f.pathToRootString.map(fromDotPath).getOrElse("")

  private val Segment = "\\.([^.\\[]+)|\\[(\\d+)\\]".r
  private[json] def fromDotPath(path: String): String =
    of(Segment.findAllMatchIn(path).map(m => Option(m.group(1)).getOrElse(m.group(2))).toList)
