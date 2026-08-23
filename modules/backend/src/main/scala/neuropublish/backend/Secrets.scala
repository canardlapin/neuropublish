package neuropublish.backend

import cats.effect.IO
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Random secrets and one-way hashes. Secrets (session cookies, user tokens, publisher credentials,
  * share links) are shown once and stored only as SHA-256; passwords as salted PBKDF2-HMAC-SHA256.
  */
object Secrets:
  private val random = new SecureRandom()
  private val Iterations = 100_000

  def randomBytes(n: Int): IO[Array[Byte]] = IO {
    val b = new Array[Byte](n)
    random.nextBytes(b)
    b
  }

  /** URL-safe, unpadded base64 of `n` random bytes (24 bytes → 32 chars). */
  def token(n: Int = 32): IO[String] =
    randomBytes(n).map(Base64.getUrlEncoder.withoutPadding.encodeToString)

  /** RFC 8628 user code: eight characters from an alphabet without I/O/0/1, as `XXXX-XXXX`. */
  val UserCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
  def userCode: IO[String] = IO {
    val chars = Array.fill(8)(UserCodeAlphabet(random.nextInt(UserCodeAlphabet.length)))
    s"${chars.take(4).mkString}-${chars.drop(4).mkString}"
  }

  /** Accepts what a person typed: case and separators are not significant. */
  def normalizeUserCode(s: String): Option[String] =
    val core = s.toUpperCase.filter(c => UserCodeAlphabet.contains(c))
    if core.length == 8 && s.forall(c => c.isLetterOrDigit || c == '-' || c == ' ') then
      Some(s"${core.take(4)}-${core.drop(4)}")
    else None

  def sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString

  final case class PasswordHash(algorithm: String, iterations: Int, salt: String, hash: String)
  object PasswordHash:
    given io.circe.Codec[PasswordHash] = io.circe.generic.semiauto.deriveCodec

  private def pbkdf2(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, 256)
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded

  def hashPassword(password: String): IO[PasswordHash] =
    randomBytes(16).flatMap(salt =>
      IO.blocking(pbkdf2(password, salt, Iterations)).map(h =>
        PasswordHash(
          "pbkdf2-hmac-sha256",
          Iterations,
          Base64.getEncoder.encodeToString(salt),
          Base64.getEncoder.encodeToString(h)
        )
      )
    )

  def verifyPassword(password: String, stored: PasswordHash): IO[Boolean] =
    IO.blocking {
      val salt = Base64.getDecoder.decode(stored.salt)
      val expected = Base64.getDecoder.decode(stored.hash)
      MessageDigest.isEqual(pbkdf2(password, salt, stored.iterations), expected)
    }
