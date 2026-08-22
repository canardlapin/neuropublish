package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.{Files, Path}
import neuropublish.protocol.json.ByteProfile

object Main extends CommandIOApp("npub", "Neuropublish publisher", version = "0.1.0-dev"):

  private val bundle = Opts.argument[String]("bundle").map(Path(_))

  private val validate =
    Opts.subcommand("validate", "Admit a bundle's manifest bytes and print its digest") {
      bundle.map { dir =>
        val manifest = dir / "manifest.json"
        Files[IO].readAll(manifest).compile.to(Array).flatMap { bytes =>
          ByteProfile.admit(bytes) match
            case Right(d) => IO.println(s"manifest  ${d.render}").as(ExitCode.Success)
            case Left(vs) =>
              vs.traverse_(v => IO.println(s"error  ${manifest}: ${v.render}")).as(ExitCode.Error)
        }
      }
    }

  def main: Opts[IO[ExitCode]] = validate
