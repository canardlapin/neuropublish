package neuropublish.backend

import cats.effect.{Clock, IO, Ref}
import scala.concurrent.duration.*

/** Small in-process fixed-window guard for the alpha's local identity provider.
  *
  * It is deliberately keyed by the normalized account identifier: repeated password guesses against
  * one account are bounded even when the reverse proxy does not forward a trustworthy client
  * address. A multi-instance deployment must replace this with a shared limiter at the ingress or
  * persistence layer; the private alpha runs one control-plane process.
  */
final class AuthRateLimiter private (
    state: Ref[IO, Map[String, AuthRateLimiter.Window]],
    config: AuthRateLimiter.Config
):
  import AuthRateLimiter.Window

  def admit(account: String): IO[Boolean] =
    Clock[IO].realTime.map(_.toMillis).flatMap { now =>
      val key = account.trim.toLowerCase
      state.modify { current =>
        val live = current.filter((_, w) => now - w.startedAtMillis < config.window.toMillis)
        live.get(key) match
          case Some(w) if w.attempts >= config.attempts => live -> false
          case Some(w) => live.updated(key, w.copy(attempts = w.attempts + 1)) -> true
          case None => live.updated(key, Window(now, 1)) -> true
      }
    }

object AuthRateLimiter:
  final case class Config(attempts: Int, window: FiniteDuration):
    require(attempts > 0, "authentication attempts must be positive")
    require(window > Duration.Zero, "authentication window must be positive")

  object Config:
    val Default: Config = Config(10, 1.minute)

    def fromEnv(env: Map[String, String]): Config =
      val attempts = env.get("NP_AUTH_ATTEMPTS").flatMap(_.toIntOption).filter(_ > 0)
        .getOrElse(Default.attempts)
      val seconds = env.get("NP_AUTH_WINDOW_SECONDS").flatMap(_.toLongOption).filter(_ > 0)
        .getOrElse(Default.window.toSeconds)
      Config(attempts, seconds.seconds)

  private final case class Window(startedAtMillis: Long, attempts: Int)

  def create(config: Config = Config.Default): IO[AuthRateLimiter] =
    Ref.of[IO, Map[String, Window]](Map.empty).map(new AuthRateLimiter(_, config))
