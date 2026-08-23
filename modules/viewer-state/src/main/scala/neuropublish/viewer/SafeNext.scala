package neuropublish.viewer

/** The `next` parameter of `/login?next=…` may only send the browser back to a path on this origin
  * (no open redirect). Pure so it is tested on the JVM; the frontend additionally checks the
  * browser's own `URL` parse resolves to the same origin.
  */
object SafeNext:
  /** `Some(path)` for a same-origin path, `None` otherwise. Accepted: a path starting with `/` that
    * is not protocol-relative (`//host`, `/\host` — browsers treat `\` as `/`) and carries no
    * control characters; or an absolute URL on `origin`, reduced to its path. Leading and trailing
    * whitespace is stripped first because URL parsers strip it too.
    */
  def accept(next: String, origin: String): Option[String] =
    val n = next.trim
    val o = origin.trim.stripSuffix("/")
    if n.isEmpty || n.exists(c => c < ' ' || c == '') then None
    else if n.startsWith("//") || n.startsWith("/\\") then None
    else if n.startsWith("/") then Some(n)
    else if o.nonEmpty && n.startsWith(o + "/") then accept(n.drop(o.length), o)
    else None
