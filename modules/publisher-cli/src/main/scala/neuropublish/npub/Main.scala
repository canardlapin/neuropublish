package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.Path

object Main extends CommandIOApp("npub", "Neuropublish publisher", version = "0.1.0-dev"):

  private val out: String => IO[Unit] = IO.println
  private val bundle = Opts.argument[String]("bundle").map(Path(_))

  /** `--json`: one JSON document on stdout, progress on stderr (see [[Report]]). */
  private val jsonFlag =
    Opts.flag("json", "print one JSON document on stdout; progress goes to stderr").orFalse

  private val server =
    Opts.option[String]("server", "control-plane base URL", metavar = "url")
      .withDefault("http://127.0.0.1:8080")

  private val projectOpt =
    Opts.option[String]("project", "workspace/project", metavar = "ws/proj").mapValidated { s =>
      s.split('/') match
        case Array(ws, p) if ws.nonEmpty && p.nonEmpty => (ws, p).validNel
        case _ => "--project must be workspace/project".invalidNel
    }

  /** `--token` is accepted for scripts but discouraged: it lands in shell history. */
  private val tokenOpts: Opts[(Option[String], Option[String])] =
    (
      Opts.option[String](
        "token",
        "bearer token (discouraged; prefer `npub login` or NP_TOKEN)",
        metavar = "token"
      ).orNone,
      Opts.env[String]("NP_TOKEN", "project credential for batch jobs").orNone
    ).tupled

  /** Resolves a token per the documented precedence, printing the `--token` warning. */
  private def withToken(srv: String, t: (Option[String], Option[String]), json: Boolean = false)(
      body: (Api, String) => IO[ExitCode]
  ): IO[ExitCode] =
    def fail(tpe: String, msg: String, e: Option[Throwable]): IO[ExitCode] =
      (if json then out(e.fold(Report.failure(tpe, msg))(Report.throwable(srv)).noSpaces)
       else out(s"error  $msg")).as(ExitCode.Error)
    Credentials.load(Credentials.configDir()).map(_.get(srv)).flatMap { stored =>
      TokenSource.resolve(t._1, t._2, stored) match
        case Left(msg) => fail("token", msg, None)
        case Right(r) =>
          val warn =
            if r.source == TokenSource.Flag then
              IO.consoleForIO.errorln(
                "warning  --token is visible in process listings and shell history; prefer `npub login` or NP_TOKEN"
              )
            else IO.unit
          warn *> Api.ember(srv).use(api => body(api, r.token))
    }.handleErrorWith(e => fail("", Api.describe(srv)(e), Some(e)))

  private val validate =
    Opts.subcommand("validate", "Admit a bundle's manifest bytes and print its digest") {
      (bundle, jsonFlag).mapN((dir, json) => Validate.run(dir, json, out))
    }

  private val inspect =
    Opts.subcommand("inspect", "Print what a bundle declares and every admission problem") {
      bundle.map(dir => Inspect.run(dir, out))
    }

  private val pack =
    Opts.subcommand("pack", "Hash a staging bundle's local files into a normalized bundle") {
      (
        Opts.argument[String]("staging-dir").map(Path(_)),
        Opts.argument[String]("out.npub").map(Path(_)),
        Opts.flag("force", "replace an existing output bundle").orFalse,
        jsonFlag
      )
        .mapN((staging, dest, force, json) => Pack.run(staging, dest, force, out, json))
    }

  private val push =
    Opts.subcommand("push", "Upload missing assets and commit one immutable revision") {
      (
        bundle,
        server,
        projectOpt,
        Opts.option[String](
          "parent",
          "parent revision id (omit for the first revision)",
          metavar = "rev"
        ).orNone,
        Opts.option[String]("message", "publication message", metavar = "text").orNone,
        tokenOpts,
        jsonFlag
      ).mapN { (dir, srv, wp, parent, message, t, json) =>
        withToken(srv, t, json)((api, token) =>
          Push.run(dir, api, wp._1, wp._2, parent, message, token, out, json)
        )
      }
    }

  private val login =
    Opts.subcommand("login", "Sign in with a code approved in any browser (RFC 8628)") {
      server.map { srv =>
        Api.ember(srv).use(api => Login.run(api, Credentials.configDir(), out))
          .handleErrorWith(e => out(s"error  ${Api.describe(srv)(e)}").as(ExitCode.Error))
      }
    }

  private val logout =
    Opts.subcommand("logout", "Revoke and forget the stored token for a server") {
      server.map { srv =>
        Api.ember(srv).use(api => Login.logout(api, Credentials.configDir(), out))
          .handleErrorWith(e => out(s"error  ${Api.describe(srv)(e)}").as(ExitCode.Error))
      }
    }

  private val whoami =
    Opts.subcommand("whoami", "Show the signed-in user and memberships") {
      (server, tokenOpts).mapN((srv, t) => withToken(srv, t)(Login.whoami(_, _, out)))
    }

  private val credential =
    Opts.subcommand("credential", "Manage project-scoped publisher credentials for batch jobs") {
      val create = Opts.subcommand("create", "Create a credential; its secret is printed once") {
        (
          projectOpt,
          Opts.option[String]("name", "human-readable credential name", metavar = "name"),
          server,
          tokenOpts
        ).mapN { (wp, name, srv, t) =>
          withToken(srv, t)(Credential.create(_, _, wp._1, wp._2, name, out))
        }
      }
      val list = Opts.subcommand("list", "List a project's credentials") {
        (projectOpt, server, tokenOpts).mapN { (wp, srv, t) =>
          withToken(srv, t)(Credential.list(_, _, wp._1, wp._2, out))
        }
      }
      val revoke = Opts.subcommand("revoke", "Revoke a credential by id") {
        (
          projectOpt,
          Opts.argument[String]("credential-id"),
          server,
          tokenOpts
        ).mapN { (wp, id, srv, t) =>
          withToken(srv, t)(Credential.revoke(_, _, wp._1, wp._2, id, out))
        }
      }
      create orElse list orElse revoke
    }

  def main: Opts[IO[ExitCode]] =
    validate orElse inspect orElse pack orElse push orElse login orElse logout orElse whoami orElse
      credential
