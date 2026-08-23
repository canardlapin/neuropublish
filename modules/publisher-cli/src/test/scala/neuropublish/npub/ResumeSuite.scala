package neuropublish.npub

import cats.effect.{ExitCode, IO, Ref}
import fs2.io.file.{Files, Path}
import munit.CatsEffectSuite
import neuropublish.backend.{ProjectKey, Server}
import org.http4s.{Method, Response, Status}
import org.http4s.client.Client

/** Stage 2 exit criterion: an upload interrupted after at least one object resumes without
  * retransmitting completed objects. `npub push` against the real local-filesystem control plane
  * in-process; the first run's transport dies after its first successful object PUT.
  */
class ResumeSuite extends CatsEffectSuite:
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val token = "t"
  private val server = "http://test"

  private val app = ResourceFunFixture(
    Files[IO].tempDirectory.evalMap(dir => Server.build(dir, token, key, server).map(_.orNotFound))
  )

  private def missingLine(lines: List[String]): Int =
    lines.collectFirst {
      case l if l.startsWith("negotiating") =>
        "(\\d+) of \\d+ objects missing".r.findFirstMatchIn(l).get.group(1).toInt
    }.get

  app.test("a second push after an interrupted one skips every object already uploaded") { app =>
    val healthy = Client.fromHttpApp(app)
    for
      uploaded <- Ref[IO].of(0)
      // dies for good once one object PUT has succeeded: every later PUT fails at the transport
      flaky = Client[IO] { req =>
        if req.method == Method.PUT && req.uri.path.renderString.contains("/objects/") then
          cats.effect.Resource.eval(uploaded.get).flatMap { n =>
            if n > 0 then
              cats.effect.Resource.eval(IO.raiseError(new java.io.IOException("connection reset")))
            else
              healthy.run(req).evalTap(r =>
                if r.status == Status.NoContent then uploaded.update(_ + 1) else IO.unit
              )
          }
        else healthy.run(req)
      }
      out1 <- Ref[IO].of(List.empty[String])
      code1 <- Push.run(
        fixtures / "reference",
        new Api(flaky, server),
        "rotman",
        "sherlock",
        None,
        None,
        token,
        l => out1.update(_ :+ l)
      )
      lines1 <- out1.get
      _ = assertEquals(code1, ExitCode.Error)
      _ = assertEquals(missingLine(lines1), 5)
      done <- uploaded.get
      _ = assert(done >= 1, "at least one object must have been uploaded before the interruption")
      _ = assert(lines1.exists(_.contains("retry 2/3")), lines1.mkString("\n"))
      _ = assert(lines1.exists(_.contains("after 3 attempts")), lines1.mkString("\n"))
      out2 <- Ref[IO].of(List.empty[String])
      code2 <- Push.run(
        fixtures / "reference",
        new Api(healthy, server),
        "rotman",
        "sherlock",
        None,
        Some("resumed"),
        token,
        l => out2.update(_ :+ l)
      )
      lines2 <- out2.get
      _ = assertEquals(code2, ExitCode.Success, lines2.mkString("\n"))
      _ = assertEquals(missingLine(lines2), 5 - done)
      _ = assertEquals(
        lines2.count(l => l.startsWith("uploading") && l.endsWith("ok")),
        5 - done + 1
      ) // + manifest
    yield assert(lines2.exists(_.startsWith("view ")))
  }
