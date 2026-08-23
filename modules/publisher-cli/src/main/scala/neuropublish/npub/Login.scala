package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.Path
import neuropublish.api.*
import scala.concurrent.duration.*

/** `npub login`: RFC 8628 device flow (docs/architecture.md, "Identity and sharing"). */
object Login:
  def run(api: Api, configDir: Path, out: String => IO[Unit]): IO[ExitCode] =
    val flow =
      for
        codes <- api.orFail(api.open(Stage4.deviceStart, DeviceStart("npub")))
        _ <- out(s"Open  ${codes.verificationUriComplete}")
        _ <- out(s"Code  ${codes.userCode}")
        _ <- out("Waiting for approval…")
        start <- IO.monotonic
        deadline = start + codes.expiresIn.seconds
        granted <- poll(api, codes, deadline)
        user <-
          granted.user.fold(api.orFail(api.secured(Stage4.me, granted.token, ())).map(_.user))(
            IO.pure
          )
        _ <- Credentials.update(configDir)(
          _.put(api.server, ServerEntry(granted.token, user.email))
        )
        _ <- out(s"Signed in as ${user.email}")
      yield ExitCode.Success
    flow.handleErrorWith(e => out(s"error  ${Api.describe(api.server)(e)}").as(ExitCode.Error))

  final case class Granted(token: String, user: Option[User])

  /** Polls at the server's `interval` (never faster than once a second); `slow_down` (RFC 8628) is
    * pending with the wait doubled from then on.
    */
  private def poll(api: Api, codes: DeviceCodes, deadline: FiniteDuration): IO[Granted] =
    val expired = CliError("login code expired; run `npub login` again")
    def loop(wait: FiniteDuration): IO[Granted] =
      api.orFail(api.open(Stage4.devicePoll, DevicePoll(codes.deviceCode))).flatMap { t =>
        def again(next: FiniteDuration) =
          IO.monotonic.flatMap(now =>
            if now + next > deadline then IO.raiseError(expired) else IO.sleep(next) *> loop(next)
          )
        t.status match
          case "granted" =>
            IO.fromOption(t.token.map(Granted(_, t.user)))(CliError("granted without a token"))
          case "denied" => IO.raiseError(CliError("login denied"))
          case "expired" => IO.raiseError(expired)
          case "pending" => again(wait)
          case "slow_down" => again(wait * 2)
          case other => IO.raiseError(CliError(s"unexpected device status '$other'"))
      }
    loop(codes.interval.max(1).seconds)

  /** Revokes the stored token on the server (best effort: an unreachable server still forgets the
    * local entry, and the token expires on its own), then deletes it locally.
    */
  def logout(api: Api, configDir: Path, out: String => IO[Unit]): IO[ExitCode] =
    val server = api.server
    Credentials.load(configDir).flatMap { c =>
      c.get(server) match
        case None => out(s"not signed in to $server").as(ExitCode.Success)
        case Some(e) =>
          api.secured(Stage4.logout, e.token, ()).attempt.flatMap { revoked =>
            val note = revoked match
              case Right(Right(_)) => ""
              case Right(Left(err)) if err.code == "unauthorized" => "" // already dead
              case _ => " (could not revoke on the server; the token expires on its own)"
            Credentials.save(configDir, c.remove(server)) *>
              out(s"Signed out ${e.user} from ${Credentials.key(server)}$note")
          }.as(ExitCode.Success)
    }

  def whoami(api: Api, token: String, out: String => IO[Unit]): IO[ExitCode] =
    api.orFail(api.secured(Stage4.me, token, ())).flatMap { me =>
      out(s"${me.user.email}  (${me.user.name})") *>
        me.memberships.traverse_(m => out(s"  ${m.workspace}  ${m.role}")).as(ExitCode.Success)
    }.handleErrorWith(e => out(s"error  ${Api.describe(api.server)(e)}").as(ExitCode.Error))
