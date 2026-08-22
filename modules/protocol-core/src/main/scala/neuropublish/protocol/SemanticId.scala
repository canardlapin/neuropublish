package neuropublish.protocol

/** Namespaced, stable identifier for an open semantic record, e.g.
  * `org.neuropublish.measure/t-statistic` or `org.bbuchsbaum.fmrigds/reducer/meta-random-effects`.
  *
  * Grammar: reverse-DNS namespace, then one or more `/`-separated kebab segments.
  */
opaque type SemanticId = String

object SemanticId:
  private val Grammar = "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+(/[a-z0-9][a-z0-9-]*)+$".r

  def parse(s: String): Either[String, SemanticId] =
    if Grammar.matches(s) then Right(s)
    else Left(s"not a semantic id: '$s' (expected namespace.segments/path-segments)")

  extension (id: SemanticId)
    def value: String = id
    def namespace: String = id.takeWhile(_ != '/')

/** Reference to a versioned record schema by id, semantic version, and immutable digest. */
final case class SchemaRef(id: SemanticId, version: String, digest: Sha256)

/** Core protocol version carried by every manifest. */
final case class ProtocolVersion(major: Int, minor: Int):
  def render: String = s"$major.$minor"

object ProtocolVersion:
  val current: ProtocolVersion = ProtocolVersion(0, 1)
  def parse(s: String): Either[String, ProtocolVersion] =
    s.split('.') match
      case Array(a, b) if a.forall(_.isDigit) && b.forall(_.isDigit) =>
        Right(ProtocolVersion(a.toInt, b.toInt))
      case _ => Left(s"not a protocol version: '$s'")
