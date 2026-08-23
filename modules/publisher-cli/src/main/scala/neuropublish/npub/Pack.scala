package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}
import io.circe.Json
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{Manifest, Problem}

/** `npub pack <staging-dir> <out.npub> [--force]`: a staging bundle's `manifest.json` names local
  * files (`assets[].path`, relative to the staging directory) instead of content identities, or
  * names content identities whose bytes already sit in the normalized layout
  * (`<staging>/assets/sha256/xx/<hex>`). Pack hashes each file, writes the normalized bundle and a
  * manifest with `digest` and `size` filled in and `path` removed, then admits the written bytes
  * and prints their digest. Nothing else in the manifest is touched, so unknown fields survive.
  *
  * Pack never overwrites what a producer declared: a `digest` or `size` that disagrees with the
  * file is a problem, as is a `path` that escapes the staging directory or names a directory, an
  * asset with neither `path` nor `digest`, a digest-only asset whose bytes are not staged, an
  * unresolved catalog reference, or a non-array `assets`. Every problem is reported before anything
  * is written; an existing output bundle is left alone unless `--force` is given.
  */
object Pack:
  final case class Packed(manifestDigest: Sha256, assets: List[(String, Sha256, Long)])

  def run(staging: Path, out: Path, force: Boolean, print: String => IO[Unit]): IO[ExitCode] =
    pack(staging, out, force).flatMap {
      case Left(problems) =>
        problems.traverse_(p => print(s"error  ${p.render}")).as(ExitCode.Error)
      case Right(p) =>
        print(s"manifest  ${p.manifestDigest.render}") *>
          print(s"packed    ${p.assets.length} assets into $out") *>
          p.assets.traverse_((id, d, size) => print(s"  $id  $size B  ${d.render}"))
            .as(ExitCode.Success)
    }

  def pack(staging: Path, out: Path, force: Boolean = false): IO[Either[List[Problem], Packed]] =
    val manifestPath = staging / "manifest.json"
    Files[IO].exists(manifestPath).flatMap {
      case false => IO.pure(Left(List(Problem("", s"$manifestPath does not exist"))))
      case true =>
        Files[IO].exists(out).flatMap {
          case true if !force =>
            IO.pure(Left(List(Problem("", s"$out already exists; pass --force to replace it"))))
          case _ =>
            Files[IO].readUtf8(manifestPath).compile.string.flatMap { text =>
              _root_.io.circe.parser.parse(text) match
                case Left(e) => IO.pure(Left(List(Problem("", s"not JSON: ${e.getMessage}"))))
                case Right(json) => resolve(staging, json).flatMap {
                    case Left(ps) => IO.pure(Left(ps))
                    case Right((resolved, files)) => write(out, resolved, files)
                  }
            }
        }
    }

  /** SHA-256 and byte count of a file, streamed. */
  def hashFile(p: Path): IO[(Sha256, Long)] =
    Files[IO].readAll(p).through(Hashing.sha256).compile.lastOrError

  /** One staged asset after hashing: the manifest entry with `digest`/`size` filled in and `path`
    * removed, plus where its bytes are.
    */
  private final case class Staged(entry: Json, id: String, digest: Sha256, size: Long, file: Path)

  /** The resolved manifest plus (id, digest, size, source file) for every asset. */
  private def resolve(
      staging: Path,
      json: Json
  ): IO[Either[List[Problem], (Json, List[(String, Sha256, Long, Path)])]] =
    json.hcursor.downField("assets").focus match
      case None => IO.pure(Left(List(Problem("/assets", "assets is required"))))
      case Some(a) if !a.isArray =>
        IO.pure(Left(List(Problem("/assets", "assets must be an array"))))
      case Some(arr) =>
        val assets = arr.asArray.get.toList
        Files[IO].realPath(staging).flatMap { root =>
          assets.zipWithIndex.traverse((a, i) => resolveOne(root, a, i)).map { results =>
            val problems = results.collect { case Left(ps) => ps }.flatten
            if problems.nonEmpty then Left(problems)
            else
              val ok = results.collect { case Right(s) => s }
              val resolved = json.mapObject(_.add("assets", Json.arr(ok.map(_.entry)*)))
              Right((resolved, ok.map(s => (s.id, s.digest, s.size, s.file))))
          }
        }

  private def resolveOne(root: Path, a: Json, i: Int): IO[Either[List[Problem], Staged]] =
    val at = s"/assets/$i"
    val c = a.hcursor
    val id = c.get[String]("id").getOrElse(s"#$i")
    val declaredDigest = c.downField("digest").focus.filter(_.isString).flatMap(_.asString)
    val declaredSize = c.downField("size").focus.flatMap(_.asNumber).flatMap(_.toLong)
    val catalog = c.downField("catalog").focus.exists(_.isString)

    /** Declared `digest`/`size` must agree with the file; they are never overwritten. */
    def agree(digest: Sha256, size: Long): List[Problem] =
      List(
        declaredDigest.collect {
          case d if Sha256.parse(d).toOption.forall(_.hex != digest.hex) =>
            Problem(
              s"$at/digest",
              s"asset $id: declared digest $d does not match the file (${digest.render})"
            )
        },
        declaredSize.collect {
          case s if s != size =>
            Problem(s"$at/size", s"asset $id: declared size $s does not match the file ($size B)")
        }
      ).flatten

    def staged(file: Path): IO[Either[List[Problem], Staged]] =
      hashFile(file).map { (digest, size) =>
        val ps = agree(digest, size)
        if ps.nonEmpty then Left(ps)
        else
          val entry = a.mapObject(
            _.remove("path").add("digest", Json.fromString(digest.render))
              .add("size", Json.fromLong(size))
          )
          Right(Staged(entry, id, digest, size, file))
      }

    c.get[String]("path").toOption match
      case Some(rel) =>
        val candidate = root.resolve(rel)
        Files[IO].exists(candidate).flatMap {
          case false => IO.pure(Left(List(Problem(s"$at/path", s"asset $id: $rel does not exist"))))
          case true =>
            Files[IO].realPath(candidate).flatMap { real =>
              if !real.startsWith(root) then
                IO.pure(Left(List(Problem(
                  s"$at/path",
                  s"asset $id: $rel escapes the staging directory"
                ))))
              else
                Files[IO].isRegularFile(real).flatMap {
                  case false =>
                    IO.pure(Left(List(Problem(
                      s"$at/path",
                      s"asset $id: $rel is not a regular file"
                    ))))
                  case true => staged(real)
                }
            }
        }
      case None =>
        declaredDigest.map(Sha256.parse) match
          case Some(Right(d)) =>
            val file = root / "assets" / "sha256" / d.hex.take(2) / d.hex
            Files[IO].isRegularFile(file).flatMap {
              case true => staged(file)
              case false =>
                IO.pure(Left(List(Problem(
                  at,
                  s"asset $id: no path, and its bytes are not staged at assets/sha256/${d.hex.take(2)}/${d.hex}"
                ))))
            }
          case Some(Left(m)) => IO.pure(Left(List(Problem(s"$at/digest", s"asset $id: $m"))))
          case None if catalog =>
            IO.pure(Left(List(Problem(
              s"$at/catalog",
              s"asset $id: catalog reference has no path and no digest; resolve it to a file first"
            ))))
          case None => IO.pure(Left(List(Problem(at, s"asset $id: neither path nor digest"))))

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
            val dest = dir / d.hex
            Files[IO].createDirectories(dir) *>
              // a digest-only asset may already sit at its normalized location
              Files[IO].realPath(src).flatMap(real =>
                Files[IO].exists(dest).flatMap {
                  case true => Files[IO].realPath(dest).map(_ == real)
                  case false => IO.pure(false)
                }
              ).flatMap {
                case true => IO.unit
                case false => Files[IO].copy(src, dest, CopyFlags(CopyFlag.ReplaceExisting))
              }
          }
          _ <- fs2.Stream.emits(bytes).through(Files[IO].writeAll(out / "manifest.json"))
            .compile.drain
        yield Right(Packed(digest, files.map((id, d, size, _) => (id, d, size))))
