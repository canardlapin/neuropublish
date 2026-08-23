package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Files
import io.circe.Codec
import io.circe.generic.semiauto.*
import munit.CatsEffectSuite

/** Atomic document replacement: a reader racing any number of rewrites sees a whole document (old
  * or new), never a partial one, and never an exception; a vanished file reads as absent.
  */
class JsonFilesSuite extends CatsEffectSuite:
  final case class Doc(n: Int, payload: String)
  given Codec[Doc] = deriveCodec

  test("concurrent rewrite + read never throws and never yields a torn document") {
    Files[IO].tempDirectory.use { dir =>
      val p = dir / "doc.json"
      val payload = "x" * 20_000
      val writers = (1 to 200).toList.traverse_(i => JsonFiles.write(p, Doc(i, payload)))
      val readers = (1 to 400).toList.traverse(_ =>
        JsonFiles.read[Doc](p).map(_.forall(d => d.payload == payload && d.n >= 0))
      )
      for
        _ <- JsonFiles.write(p, Doc(0, payload))
        (_, seen) <- (writers, readers).parTupled
        last <- JsonFiles.read[Doc](p)
        parts <-
          Files[IO].list(dir).map(_.fileName.toString).filter(_.endsWith(".part")).compile.toList
      yield
        assert(seen.forall(identity), "a reader saw a torn document")
        assertEquals(last.map(_.n), Some(200))
        assertEquals(parts, Nil)
    }
  }

  test("a missing file reads as None; a missing directory lists as empty") {
    Files[IO].tempDirectory.use { dir =>
      for
        none <- JsonFiles.read[Doc](dir / "nope.json")
        bytes <- JsonFiles.readBytes(dir / "nope.bin")
        listed <- JsonFiles.list[Doc](dir / "nowhere")
        lines <- JsonFiles.readLines(dir / "nope.jsonl")
      yield assertEquals((none, bytes, listed, lines), (None, None, Nil, Nil))
    }
  }
