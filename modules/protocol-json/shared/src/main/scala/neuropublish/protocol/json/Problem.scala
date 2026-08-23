package neuropublish.protocol.json

import io.circe.{CursorOp, DecodingFailure}

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

  /** Pointer to the cursor position a circe decoding failure refers to, replayed from its operation
    * history (never from the rendered `.a[0].b` path, which cannot represent keys that contain `.`
    * or `[`).
    */
  def ofFailure(f: DecodingFailure): String = ofHistory(f.history)

  /** Replays a cursor history (most recent operation first, as circe records it) into a pointer. */
  private[json] def ofHistory(history: List[CursorOp]): String =
    val tokens = history.reverse.foldLeft(List.empty[String]) { (path, op) =>
      op match
        case CursorOp.DownField(k) => path :+ k
        case CursorOp.DownArray => path :+ "0"
        case CursorOp.DownN(n) => path :+ n.toString
        case CursorOp.MoveRight | CursorOp.MoveLeft =>
          path.lastOption.flatMap(_.toIntOption) match
            case Some(i) =>
              path.init :+ (if op == CursorOp.MoveRight then i + 1 else i - 1).toString
            case None => path
        case CursorOp.MoveUp | CursorOp.DeleteGoParent => if path.isEmpty then path else path.init
        case CursorOp.Field(k) => if path.isEmpty then path else path.init :+ k
        case _ => path
    }
    of(tokens)
