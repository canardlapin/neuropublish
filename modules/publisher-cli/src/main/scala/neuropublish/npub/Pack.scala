package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{Manifest, Problem}

/** `npub pack <staging-dir> <out.npub>`: a staging bundle's `manifest.json` names local files
  * (`assets[].path`, relative to the staging directory) instead of content identities. Pack hashes
  * each file, writes the normalized bundle (`assets/sha256/xx/<hex>`) and a manifest with `digest`
  * and `size` filled in and `path` removed, then admits the written bytes and prints their digest.
  * Nothing else in the manifest is touched, so unknown fields survive. A `path` that escapes the
  * staging directory, an asset with neither `path` nor `digest`, or an unresolved catalog reference
  * refuses the pack before anything is written.
  */
object Pack:
  final case class Packed(manifestDigest: Sha256, assets: List[(String, Sha256, Long)])

  def run(staging: Path, out: Path, print: String => IO[Unit]): IO[ExitCode] =
    pack(staging, out).flatMap {
      case Left(problems) =>
        problems.traverse_(p => print(s"error  ${p.render}")).as(ExitCode.Error)
      case Right(p) =>
        print(s"manifest  ${p.manifestDigest.render}") *>
          print(s"packed    ${p.assets.length} assets into $out") *>
          p.assets.traverse_((id, d, size) => print(s"  $id  $size B  ${d.render}"))
            .as(ExitCode.Success)
    }

  def pack(staging: Path, out: Path): IO[Either[List[Problem], Packed]] =
    val manifestPath = staging / "manifest.json"
    Files[IO].exists(manifestPath).flatMap {
      case false => IO.pure(Left(List(Problem("", s"$manifestPath does not exist"))))
      case true =>
        Files[IO].readUtf8(manifestPath).compile.string.flatMap { text =>
          _root_.io.circe.parser.parse(text) match
            case Left(e) => IO.pure(Left(List(Problem("", s"not JSON: ${e.getMessage}"))))
            case Right(json) => resolve(staging, json).flatMap {
                case Left(ps) => IO.pure(Left(ps))
                case Right((resolved, files)) => write(out, resolved, files)
              }
        }
    }

  /** The resolved manifest plus (digest, source file) for every hashed asset. */
  private def resolve(
      staging: Path,
      json: Json
  ): IO[Either[List[Problem], (Json, List[(String, Sha256, Long, Path)])]] =
    val assets = json.hcursor.downField("assets").as[List[Json]].toOption.getOrElse(Nil)
    Files[IO].realPath(staging).flatMap { root =>
      assets.zipWithIndex.traverse { (a, i) =>
        val c = a.hcursor
        val hasDigest = c.downField("digest").focus.exists(_.isString)
        val catalog = c.downField("catalog").focus.exists(_.isString)
        val id = c.get[String]("id").getOrElse(s"#$i")
        c.get[String]("path").toOption match
          case None if hasDigest => IO.pure(Right((a, None)))
          case None if catalog =>
            IO.pure(Left(Problem(
              s"/assets/$i/catalog",
              s"asset $id: catalog reference has no path and no digest; resolve it to a file first"
            )))
          case None =>
            IO.pure(Left(Problem(s"/assets/$i", s"asset $id: neither path nor digest")))
          case Some(rel) =>
            val candidate = root.resolve(rel)
            Files[IO].exists(candidate).flatMap {
              case false =>
                IO.pure(Left(Problem(s"/assets/$i/path", s"asset $id: $rel does not exist")))
              case true =>
                Files[IO].realPath(candidate).flatMap { real =>
                  if !real.startsWith(root) then
                    IO.pure(Left(Problem(
                      s"/assets/$i/path",
                      s"asset $id: $rel escapes the staging directory"
                    )))
                  else
                    Files[IO].readAll(real).compile.to(Array).map { bytes =>
                      val d = Sha256.of(bytes)
                      val size = bytes.length.toLong
                      val updated = a.mapObject(
                        _.remove("path").add("digest", Json.fromString(d.render))
                          .add("size", Json.fromLong(size))
                      )
                      Right((updated, Some((id, d, size, real))))
                    }
                }
            }
      }.map { results =>
        val problems = results.collect { case Left(p) => p }
        if problems.nonEmpty then Left(problems)
        else
          val ok = results.collect { case Right(r) => r }
          val resolved = json.mapObject(_.add("assets", Json.arr(ok.map(_._1)*)))
          Right((resolved, ok.flatMap(_._2)))
      }
    }

  private def write(
      out: Path,
      manifest: Json,
      files: List[(String, Sha256, Long, Path)]
  ): IO[Either[List[Problem], Packed]] =
    val bytes = (manifest.spaces2 + "\n").getBytes("UTF-8")
    Manifest.parse(bytes) match
      case Left(ps) => IO.pure(Left(ps))
      case Right((digest, _)) =>
        for
          _ <- Files[IO].createDirectories(out / "assets" / "sha256")
          _ <- files.traverse_ { (_, d, _, src) =>
            val dir = out / "assets" / "sha256" / d.hex.take(2)
            Files[IO].createDirectories(dir) *> Files[IO].copy(
              src,
              dir / d.hex,
              fs2.io.file.CopyFlags(fs2.io.file.CopyFlag.ReplaceExisting)
            )
          }
          _ <- fs2.Stream.emits(bytes).through(Files[IO].writeAll(out / "manifest.json"))
            .compile.drain
        yield Right(Packed(digest, files.map((id, d, size, _) => (id, d, size))))
