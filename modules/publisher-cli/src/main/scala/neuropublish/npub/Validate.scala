package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{Manifest, Problem}

/** `npub validate <bundle> [--json]`: admit the bundle's `manifest.json` bytes and print its digest.
  * A failure to read the file is reported the same way as any other failure (never a stack trace).
  */
object Validate:
  enum Outcome:
    case Admitted(digest: Sha256, declared: Int, volumes: Int)
    case Rejected(problems: List[Problem])
    case Failed(error: Throwable)

  def run(dir: Path, json: Boolean, out: String => IO[Unit]): IO[ExitCode] =
    outcome(dir).flatMap { o =>
      val lines = if json then List(render(o).noSpaces) else human(dir, o)
      lines.traverse_(out).as(if o.isInstanceOf[Outcome.Admitted] then ExitCode.Success
      else ExitCode.Error)
    }

  def outcome(dir: Path): IO[Outcome] =
    Files[IO].readAll(dir / "manifest.json").compile.to(Array).attempt.map {
      case Left(e) => Outcome.Failed(e)
      case Right(bytes) => of(bytes)
    }

  def of(bytes: Array[Byte]): Outcome =
    Manifest.parse(bytes) match
      case Right((d, m)) => Outcome.Admitted(d, m.assets.length, m.volumeAssetIds.length)
      case Left(problems) => Outcome.Rejected(problems)

  def human(dir: Path, o: Outcome): List[String] = o match
    case Outcome.Admitted(d, declared, volumes) =>
      List(s"manifest  ${d.render}", s"assets    $declared declared, $volumes volume")
    case Outcome.Rejected(problems) =>
      s"error  ${dir / "manifest.json"}: ${problems.length} problem(s)" ::
        problems.map(p => s"error  ${p.render}")
    case Outcome.Failed(e) => List(s"error  ${Api.describe("")(e)}")

  def render(o: Outcome): Json = o match
    case Outcome.Admitted(d, declared, volumes) =>
      Report.success(
        "digest" -> Json.fromString(d.render),
        "assets" -> Json.obj(
          "declared" -> Json.fromInt(declared),
          "volume" -> Json.fromInt(volumes)
        )
      )
    case Outcome.Rejected(problems) => Report.rejected(problems)
    case Outcome.Failed(e) => Report.throwable("")(e)
