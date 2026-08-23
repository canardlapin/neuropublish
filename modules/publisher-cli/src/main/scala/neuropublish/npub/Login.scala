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

  private def poll(api: Api, codes: DeviceCodes, deadline: FiniteDuration): IO[Granted] =
    val interval = codes.interval.max(0).seconds
    def loop: IO[Granted] =
      api.orFail(api.open(Stage4.devicePoll, DevicePoll(codes.deviceCode))).flatMap { t =>
        t.status match
          case "granted" =>
            IO.fromOption(t.token.map(Granted(_, t.user)))(CliError("granted without a token"))
          case "denied" => IO.raiseError(CliError("login denied"))
          case "expired" => IO.raiseError(CliError("login code expired; run `npub login` again"))
          case "pending" =>
            IO.monotonic.flatMap { now =>
              if now + interval > deadline then
                IO.raiseError(CliError("login code expired; run `npub login` again"))
              else IO.sleep(interval) *> loop
            }
          case other => IO.raiseError(CliError(s"unexpected device status '$other'"))
      }
    loop

  def logout(server: String, configDir: Path, out: String => IO[Unit]): IO[ExitCode] =
    Credentials.load(configDir).flatMap { c =>
      c.get(server) match
        case None => out(s"not signed in to $server").as(ExitCode.Success)
        case Some(e) =>
          Credentials.save(configDir, c.remove(server)) *>
            out(s"Signed out ${e.user} from ${Credentials.key(server)}").as(ExitCode.Success)
    }

  def whoami(api: Api, token: String, out: String => IO[Unit]): IO[ExitCode] =
    api.orFail(api.secured(Stage4.me, token, ())).flatMap { me =>
      out(s"${me.user.email}  (${me.user.name})") *>
        me.memberships.traverse_(m => out(s"  ${m.workspace}  ${m.role}")).as(ExitCode.Success)
    }.handleErrorWith(e => out(s"error  ${Api.describe(api.server)(e)}").as(ExitCode.Error))
