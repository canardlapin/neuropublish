package neuropublish.npub

import cats.effect.{IO, Ref}
import fs2.io.file.{Files, Path}
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Problem

/** The `--json` output mode: one document on stdout whose shape a wrapper can rely on. */
class JsonOutputSuite extends CatsEffectSuite:
  private val tmp = ResourceFunFixture(Files[IO].tempDirectory)
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath))
    .getOrElse(fail("fixtures not found"))

  private def capture: IO[(Ref[IO, List[String]], String => IO[Unit])] =
    Ref[IO].of(List.empty[String]).map(r => r -> (s => r.update(_ :+ s)))

  private def one(lines: List[String]): Json =
    assertEquals(lines.length, 1, lines)
    parse(lines.head).fold(e => fail(e.getMessage), identity)

  private def field(j: Json, path: String*): Json =
    path.foldLeft(j.hcursor: io.circe.ACursor)(_.downField(_)).focus.getOrElse(Json.Null)

  tmp.test("validate --json: an admitted bundle reports ok, digest and asset counts") { _ =>
    capture.flatMap { (seen, out) =>
      Validate.run(fixtures / "reference", json = true, out) *> seen.get.map { lines =>
        val j = one(lines)
        assertEquals(field(j, "ok"), Json.True)
        assert(field(j, "digest").asString.exists(_.startsWith("sha256:")), j)
        assert(field(j, "assets", "declared").asNumber.flatMap(_.toInt).exists(_ > 0), j)
        assert(field(j, "assets", "volume").asNumber.isDefined, j)
        assertEquals(field(j, "problems"), Json.Null)
      }
    }
  }

  tmp.test("validate --json: admission problems are pointer/message records, never an error") {
    dir =>
      val manifest = """{"core":"0.1","title":"T","assets":[{"id":"a"}]}"""
      for
        _ <- fs2.Stream.emits(manifest.getBytes("UTF-8"))
          .through(Files[IO].writeAll(dir / "manifest.json")).compile.drain
        (seen, out) <- capture
        code <- Validate.run(dir, json = true, out)
        lines <- seen.get
      yield
        val j = one(lines)
        assertEquals(code, cats.effect.ExitCode.Error)
        assertEquals(field(j, "ok"), Json.False)
        val problems = field(j, "problems").asArray.getOrElse(fail(s"no problems array: $j"))
        assert(problems.nonEmpty, j)
        problems.foreach { p =>
          assert(field(p, "pointer").isString, p)
          assert(field(p, "message").isString, p)
        }
        assertEquals(field(j, "error"), Json.Null)
  }

  tmp.test("validate --json: a missing manifest is an error record with a type, not a problem") {
    dir =>
      capture.flatMap { (seen, out) =>
        Validate.run(dir / "nope", json = true, out).flatMap { code =>
          seen.get.map { lines =>
            val j = one(lines)
            assertEquals(code, cats.effect.ExitCode.Error)
            assertEquals(field(j, "ok"), Json.False)
            assertEquals(field(j, "error", "type"), Json.fromString("NoSuchFileException"))
            assert(field(j, "error", "message").asString.exists(_.contains("manifest.json")), j)
            assertEquals(field(j, "problems"), Json.Null)
          }
        }
      }
  }

  tmp.test("validate (human): a missing manifest prints one error line, no stack trace") { dir =>
    capture.flatMap { (seen, out) =>
      Validate.run(dir / "nope", json = false, out) *> seen.get.map { lines =>
        assertEquals(lines.length, 1)
        assert(lines.head.startsWith("error  NoSuchFileException"), lines)
      }
    }
  }

  test("pack --json renders digest, dir and one record per asset") {
    val d = Sha256.of("x".getBytes)
    val j = Pack.render(Pack.Packed(d, List(("a", d, 7L))), Path("out.npub"))
    assertEquals(field(j, "ok"), Json.True)
    assertEquals(field(j, "digest"), Json.fromString(d.render))
    assertEquals(field(j, "dir"), Json.fromString("out.npub"))
    val a = field(j, "assets").asArray.get.head
    assertEquals(field(a, "id"), Json.fromString("a"))
    assertEquals(field(a, "size"), Json.fromLong(7L))
    assertEquals(field(a, "digest"), Json.fromString(d.render))
  }

  tmp.test("pack --json: a problem is a problems array with pointers") { dir =>
    capture.flatMap { (seen, out) =>
      Pack.run(dir, dir / "out.npub", force = false, out, json = true).flatMap { code =>
        seen.get.map { lines =>
          val j = one(lines)
          assertEquals(code, cats.effect.ExitCode.Error)
          assertEquals(field(j, "ok"), Json.False)
          val ps = field(j, "problems").asArray.get
          assertEquals(ps.length, 1)
          assertEquals(field(ps.head, "pointer"), Json.fromString(""))
          assert(field(ps.head, "message").asString.exists(_.contains("does not exist")), j)
        }
      }
    }
  }

  test("push --json: every outcome has a stable shape") {
    import Push.Outcome.*
    val committed = Push.render(Committed(Some("r1"), "r2", "sha256:ab", "http://s/r2", "http://s/v"))
    assertEquals(field(committed, "ok"), Json.True)
    assertEquals(field(committed, "unchanged"), Json.False)
    assertEquals(field(committed, "revision"), Json.fromString("r2"))
    assertEquals(field(committed, "parent"), Json.fromString("r1"))
    assertEquals(field(committed, "viewUrl"), Json.fromString("http://s/v"))
    assertEquals(
      field(Push.render(Committed(None, "r1", "d", "u", "v")), "parent"),
      Json.Null
    )

    val unchanged = Push.render(Unchanged("r2"))
    assertEquals(field(unchanged, "ok"), Json.True)
    assertEquals(field(unchanged, "unchanged"), Json.True)
    assertEquals(field(unchanged, "revision"), Json.fromString("r2"))

    val stale = Push.render(StaleParent("parent is not the head", Some("r9")))
    assertEquals(field(stale, "ok"), Json.False)
    assertEquals(field(stale, "error", "type"), Json.fromString("stale_parent"))
    assertEquals(field(stale, "error", "head"), Json.fromString("r9"))

    val rejected = Push.render(ManifestRejected(List(Problem("/title", "title is required"))))
    assertEquals(field(rejected, "ok"), Json.False)
    assertEquals(field(rejected, "error", "type"), Json.fromString("manifest_rejected"))
    assertEquals(
      field(rejected, "problems").asArray.get.map(p => field(p, "pointer")),
      Vector(Json.fromString("/title"))
    )

    val api = Push.render(Rejected("forbidden", "no", Nil))
    assertEquals(field(api, "error", "type"), Json.fromString("forbidden"))
    assertEquals(field(api, "error", "message"), Json.fromString("no"))

    val failed = Push.render(Failed(CliError("asset x not found")))
    assertEquals(field(failed, "error", "type"), Json.fromString("cli"))
    assertEquals(field(failed, "error", "message"), Json.fromString("asset x not found"))
  }

  test("push (human) keeps its line format for every outcome") {
    import Push.Outcome.*
    assertEquals(
      Push.human("s", Committed(None, "r1", "d", "u", "v")).head,
      "committing  parent (none) -> r1  ok"
    )
    assertEquals(Push.human("s", Unchanged("r1")), List("unchanged   already published as r1"))
    assert(Push.human("s", StaleParent("m", Some("r9"))).head.endsWith("Re-run with --parent r9"))
    assert(Push.human("s", Failed(new java.net.ConnectException())).head.contains("cannot reach s"))
  }
