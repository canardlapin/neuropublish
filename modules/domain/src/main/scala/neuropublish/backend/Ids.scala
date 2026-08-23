package neuropublish.backend

/** Path-parameter grammar. Anything else is a 404, never a filesystem path. */
object Ids:
  private val Grammar = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$".r
  def valid(s: String): Boolean = Grammar.matches(s)
