package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.{Files, Path}
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{ByteProfile, Manifest}

object Main extends CommandIOApp("npub", "Neuropublish publisher", version = "0.1.0-dev"):

  private val bundle = Opts.argument[String]("bundle").map(Path(_))

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
        Opts.option[String](
          "server",
          "control-plane base URL",
          metavar = "url"
        ).withDefault("http://127.0.0.1:8080"),
        Opts.option[String]("project", "workspace/project", metavar = "ws/proj"),
        Opts.option[String](
          "parent",
          "parent revision id (omit for the first revision)",
          metavar = "rev"
        ).orNone,
        Opts.option[String]("message", "publication message", metavar = "text").orNone,
        Opts.env[String]("NP_TOKEN", "publisher token")
      ).mapN { (dir, server, project, parent, message, token) =>
        project.split('/') match
          case Array(ws, p) => Push.run(dir, server, ws, p, parent, message, token)
          case _ => IO.println("error  --project must be workspace/project").as(ExitCode.Error)
      }
    }

  def main: Opts[IO[ExitCode]] = validate orElse push
