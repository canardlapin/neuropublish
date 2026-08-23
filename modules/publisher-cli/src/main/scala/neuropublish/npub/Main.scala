package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.{Files, Path}
import neuropublish.protocol.json.Manifest

object Main extends CommandIOApp("npub", "Neuropublish publisher", version = "0.1.0-dev"):

  private val out: String => IO[Unit] = IO.println
  private val bundle = Opts.argument[String]("bundle").map(Path(_))

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
  private def withToken(srv: String, t: (Option[String], Option[String]))(
      body: (Api, String) => IO[ExitCode]
  ): IO[ExitCode] =
    Credentials.load(Credentials.configDir()).map(_.get(srv)).flatMap { stored =>
      TokenSource.resolve(t._1, t._2, stored) match
        case Left(msg) => out(s"error  $msg").as(ExitCode.Error)
        case Right(r) =>
          val warn =
            if r.source == TokenSource.Flag then
              IO.consoleForIO.errorln(
                "warning  --token is visible in process listings and shell history; prefer `npub login` or NP_TOKEN"
              )
            else IO.unit
          warn *> Api.ember(srv).use(api => body(api, r.token))
    }.handleErrorWith(e => out(s"error  ${Api.describe(srv)(e)}").as(ExitCode.Error))

  private val validate =
    Opts.subcommand("validate", "Admit a bundle's manifest bytes and print its digest") {
      bundle.map { dir =>
        Files[IO].readAll(dir / "manifest.json").compile.to(Array).flatMap { bytes =>
          Manifest.parse(bytes) match
            case Right((d, m)) =>
              IO.println(s"manifest  ${d.render}") *> IO.println(
                s"assets    ${m.assets.length} declared, ${m.volumeAssetIds.length} volume"
              ).as(ExitCode.Success)
            case Left(msg) =>
              IO.println(s"error  ${dir / "manifest.json"}: $msg").as(ExitCode.Error)
        }
      }
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
        tokenOpts
      ).mapN { (dir, srv, wp, parent, message, t) =>
        withToken(srv, t)((api, token) =>
          Push.run(dir, api, wp._1, wp._2, parent, message, token, out)
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
    validate orElse push orElse login orElse logout orElse whoami orElse credential
