package neuropublish.protocol

/** A SHA-256 content identity, `sha256:<64 lowercase hex>`. */
opaque type Sha256 = String

object Sha256:
  private val Hex = "^[0-9a-f]{64}$".r

  def parse(s: String): Either[String, Sha256] =
    val body = if s.startsWith("sha256:") then s.drop(7) else s
    if Hex.matches(body) then Right(body)
    else Left(s"not a sha256 identity: '$s' (expected sha256:<64 lowercase hex>)")

  def unsafe(hex: String): Sha256 =
    parse(hex).fold(m => throw IllegalArgumentException(m), identity)

  /** Digest bytes with a pure-Scala SHA-256 so JVM and Scala.js agree byte for byte. */
  def of(bytes: Array[Byte]): Sha256 = Sha256Digest.hex(bytes)

  extension (d: Sha256)
    def hex: String = d
    def render: String = s"sha256:$d"
