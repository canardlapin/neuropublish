package neuropublish.npub

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import neuropublish.api.ApiError
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import sttp.tapir.Endpoint
import sttp.tapir.client.http4s.Http4sClientInterpreter

/** Thin tapir-over-http4s caller bound to one server. Tests build it on `Client.fromHttpApp`; the
  * CLI uses Ember.
  */
final class Api(client: Client[IO], val server: String):
  /** The underlying client, for transfers that follow a server-issued URL (uploads). */
  def raw: Client[IO] = client
  private val base = Some(Api.uri(server))
  private val interp = Http4sClientInterpreter[IO]()

  /** Bearer-secured endpoints carry `(bearer, np_session cookie)`; the CLI never has a cookie. */
  def secured[I, E, O](
      ep: Endpoint[(Option[String], Option[String]), I, E, O, Any],
      token: String,
      in: I
  ): IO[Either[E, O]] =
    val (req, handle) =
      interp.toSecureRequestThrowDecodeFailures(ep, base).apply((Some(token), None)).apply(in)
    client.run(req).use(handle)

  def open[I, E, O](ep: Endpoint[Unit, I, E, O, Any], in: I): IO[Either[E, O]] =
    val (req, handle) = interp.toRequestThrowDecodeFailures(ep, base).apply(in)
    client.run(req).use(handle)

  /** Lifts the contract's error body into a `CliError` with its code. */
  def orFail[O](r: IO[Either[ApiError, O]]): IO[O] =
    r.flatMap(e => IO.fromEither(e.leftMap(e => CliError(s"${e.code}: ${e.message}"))))

object Api:
  def uri(server: String): Uri =
    Uri.fromString(server).getOrElse(throw CliError(s"--server is not a URL: $server"))

  def ember(server: String): Resource[IO, Api] =
    Resource.eval(IO(uri(server))) *> EmberClientBuilder.default[IO].build.map(new Api(_, server))

  /** Maps transport and CLI failures to a one-line message. */
  def describe(server: String)(e: Throwable): String = e match
    case CliError(m) => m
    case _: java.net.ConnectException | _: java.nio.channels.UnresolvedAddressException =>
      s"cannot reach $server"
    case e => s"${e.getClass.getSimpleName}: ${e.getMessage}"
