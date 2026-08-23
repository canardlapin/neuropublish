package neuropublish.npub

import org.http4s.Uri

/** The origin of a URL (RFC 6454): scheme, host, and port, with case folded and the scheme's
  * default port filled in. Two URLs share an origin only when all three agree; the bearer token is
  * sent to an instruction URL only on the control plane's origin.
  */
final case class Origin(scheme: String, host: String, port: Int):
  def render: String = s"$scheme://${Origin.renderHost(host)}:$port"

  /** Loopback hosts are safe for plain `http`; any other host should be `https`. */
  def isLoopback: Boolean =
    host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") ||
      host == "::1" || host == "0:0:0:0:0:0:0:1"

object Origin:
  private def renderHost(h: String): String = if h.contains(':') then s"[$h]" else h

  def defaultPort(scheme: String): Option[Int] = scheme match
    case "http" => Some(80)
    case "https" => Some(443)
    case _ => None

  def parse(url: String): Option[Origin] =
    Uri.fromString(url).toOption.flatMap(of)

  def of(uri: Uri): Option[Origin] =
    for
      scheme <- uri.scheme.map(_.value.toLowerCase)
      authority <- uri.authority
      port <- authority.port.orElse(defaultPort(scheme))
    yield Origin(scheme, authority.host.value.toLowerCase.stripPrefix("[").stripSuffix("]"), port)

  /** Whether `url` is on the same origin as `server`; unparsable input never matches. */
  def same(url: String, server: String): Boolean =
    (parse(url), parse(server)) match
      case (Some(a), Some(b)) => a == b
      case _ => false
