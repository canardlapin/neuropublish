package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}
import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import java.nio.file.NoSuchFileException

/** One JSON document per file under the data dir — the local persistence. Every write is a temp
  * file plus an atomic rename, so a crash never leaves a half-written record and a concurrent
  * reader sees either the old or the new document. Reads never check-then-open: a file that
  * vanishes between a listing and its read is simply absent (`None`).
  */
object JsonFiles:
  /** Replace `target` atomically with the bytes of `tmp` (same directory). */
  val Replace: CopyFlags = CopyFlags(CopyFlag.ReplaceExisting, CopyFlag.AtomicMove)

  /** A unique sibling of `p` for staging a write. */
  def tempFor(p: Path): Path =
    p.parent.get / s"${p.fileName}.${java.util.UUID.randomUUID().toString.take(8)}.part"

  /** `None` when the file does not exist (racing a rename or a delete included). */
  def readBytes(p: Path): IO[Option[Array[Byte]]] =
    Files[IO].readAll(p).compile.to(Array).map(Some(_)).recover {
      case _: NoSuchFileException => None
    }

  def read[A: Decoder](p: Path): IO[Option[A]] =
    readBytes(p).flatMap {
      case None => IO.none
      case Some(b) =>
        IO.fromEither(io.circe.parser.decode[A](new String(b, "UTF-8"))).map(Some(_))
    }

  def writeBytes(p: Path, bytes: Array[Byte]): IO[Unit] =
    val tmp = tempFor(p)
    Files[IO].createDirectories(p.parent.get) *>
      (fs2.Stream.emits(bytes).through(Files[IO].writeAll(tmp)).compile.drain *>
        Files[IO].move(tmp, p, Replace)).onError(_ => Files[IO].deleteIfExists(tmp).void)

  def write[A: Encoder](p: Path, a: A): IO[Unit] =
    writeBytes(p, a.asJson.spaces2.getBytes("UTF-8"))

  /** Every `*.json` document directly under `dir` (empty when the directory does not exist). */
  def list[A: Decoder](dir: Path): IO[List[A]] =
    paths(dir, ".json").flatMap(_.traverse(read[A]).map(_.flatten))

  /** Files under `dir` with `suffix`, sorted by name; empty when the directory does not exist. */
  def paths(dir: Path, suffix: String): IO[List[Path]] =
    Files[IO].list(dir).filter(_.fileName.toString.endsWith(suffix)).compile.toList
      .map(_.sortBy(_.fileName.toString))
      .recover { case _: NoSuchFileException => Nil }

  /** Append one line (JSONL). Appends are not atomic across processes; one server owns the dir. */
  def appendLine(p: Path, line: String): IO[Unit] =
    Files[IO].createDirectories(p.parent.get) *>
      fs2.Stream.emit(line + "\n").through(Files[IO].writeUtf8(
        p,
        fs2.io.file.Flags.Append
      )).compile.drain

  def readLines(p: Path): IO[List[String]] =
    Files[IO].readUtf8Lines(p).filter(_.nonEmpty).compile.toList.recover {
      case _: NoSuchFileException => Nil
    }
