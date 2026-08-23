package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}
import io.circe.{Decoder, Encoder}
import io.circe.syntax.*

/** One JSON document per file under the data dir — the Stage 4 local persistence. Every write is a
  * temp file plus atomic rename, so a crash never leaves a half-written record. Stage 2+ replaces
  * each store with PostgreSQL behind the same algebras.
  */
object JsonFiles:
  def read[A: Decoder](p: Path): IO[Option[A]] =
    Files[IO].exists(p).flatMap {
      case false => IO.none
      case true =>
        Files[IO].readUtf8(p).compile.string.flatMap(s =>
          IO.fromEither(io.circe.parser.decode[A](s)).map(Some(_))
        )
    }

  def write[A: Encoder](p: Path, a: A): IO[Unit] =
    val tmp = p.parent.get / s"${p.fileName}.${java.util.UUID.randomUUID().toString.take(8)}.part"
    Files[IO].createDirectories(p.parent.get) *>
      fs2.Stream.emit(a.asJson.spaces2).through(Files[IO].writeUtf8(tmp)).compile.drain *>
      Files[IO].move(tmp, p, CopyFlags(CopyFlag.ReplaceExisting))

  /** Every `*.json` document directly under `dir` (empty when the directory does not exist). */
  def list[A: Decoder](dir: Path): IO[List[A]] =
    Files[IO].exists(dir).flatMap {
      case false => IO.pure(Nil)
      case true =>
        Files[IO].list(dir).filter(_.fileName.toString.endsWith(".json")).compile.toList
          .flatMap(_.sortBy(_.fileName.toString).traverse(read[A]).map(_.flatten))
    }

  /** Append one line (JSONL). Appends are not atomic across processes; one server owns the dir. */
  def appendLine(p: Path, line: String): IO[Unit] =
    Files[IO].createDirectories(p.parent.get) *>
      fs2.Stream.emit(line + "\n").through(Files[IO].writeUtf8(
        p,
        fs2.io.file.Flags.Append
      )).compile.drain

  def readLines(p: Path): IO[List[String]] =
    Files[IO].exists(p).flatMap {
      case false => IO.pure(Nil)
      case true => Files[IO].readUtf8Lines(p).filter(_.nonEmpty).compile.toList
    }
