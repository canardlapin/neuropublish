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

  /** The object digest a PUT addresses, from its `/objects/{digest}` path. */
  private def objectDigest(req: org.http4s.Request[IO]): Option[String] =
    Option.when(req.method == Method.PUT)(req.uri.path.renderString)
      .flatMap(p => "/objects/([^/]+)$".r.findFirstMatchIn(p).map(_.group(1)))

  /** Records the digest of every object PUT a client completes with 204. */
  private def recording(
      inner: Client[IO],
      done: Ref[IO, Set[String]]
  ): Client[IO] = Client[IO] { req =>
    objectDigest(req) match
      case Some(d) =>
        inner.run(req).evalTap(r =>
          if r.status == Status.NoContent then done.update(_ + d) else IO.unit
        )
      case None => inner.run(req)
  }

  app.test("a second push after an interrupted one skips every object already uploaded") { app =>
    val healthy = Client.fromHttpApp(app)
    for
      first <- Ref[IO].of(Set.empty[String])
      second <- Ref[IO].of(Set.empty[String])
      // dies for good once one object PUT has succeeded: every later PUT fails at the transport
      flaky = Client[IO] { req =>
        if objectDigest(req).isDefined then
          cats.effect.Resource.eval(first.get).flatMap { n =>
            if n.nonEmpty then
              cats.effect.Resource.eval(IO.raiseError(new java.io.IOException("connection reset")))
            else recording(healthy, first).run(req)
          }
        else healthy.run(req)
      }
      all <- Files[IO].readAll(fixtures / "reference" / "manifest.json").compile.to(Array)
        .map(b =>
          neuropublish.protocol.json.Manifest.parse(b).toOption.get._2.assets
            .map(_.digest.render).toSet
        )
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
      _ = assertEquals(missingLine(lines1), 11)
      done <- first.get
      _ =
        assert(done.nonEmpty, "at least one object must have been uploaded before the interruption")
      _ = assert(done.subsetOf(all), done)
      _ = assert(lines1.exists(_.contains("retry 2/3")), lines1.mkString("\n"))
      _ = assert(lines1.exists(_.contains("after 3 attempts")), lines1.mkString("\n"))
      out2 <- Ref[IO].of(List.empty[String])
      code2 <- Push.run(
        fixtures / "reference",
        new Api(recording(healthy, second), server),
        "rotman",
        "sherlock",
        None,
        Some("resumed"),
        token,
        l => out2.update(_ :+ l)
      )
      lines2 <- out2.get
      _ = assertEquals(code2, ExitCode.Success, lines2.mkString("\n"))
      resumed <- second.get
      // the second session asks for exactly the objects the interruption left behind
      _ = assertEquals(resumed, all -- done)
      _ = assertEquals(missingLine(lines2), resumed.size)
      _ = assertEquals(
        lines2.count(l => l.startsWith("uploading") && l.endsWith("ok")),
        resumed.size + 1
      ) // + manifest
    yield assert(lines2.exists(_.startsWith("view ")))
  }

  app.test("re-pushing the bundle that is already the head is reported, not rejected") { app =>
    val healthy = Client.fromHttpApp(app)
    def push(out: Ref[IO, List[String]]) = Push.run(
      fixtures / "reference",
      new Api(healthy, server),
      "rotman",
      "sherlock",
      None,
      None,
      token,
      l => out.update(_ :+ l)
    )
    for
      out1 <- Ref[IO].of(List.empty[String])
      code1 <- push(out1)
      _ = assertEquals(code1, ExitCode.Success)
      lines1 <- out1.get
      head = lines1.collectFirst {
        case l if l.startsWith("committing") => l.split(" -> ")(1).trim.split(" ")(0)
      }.get
      out2 <- Ref[IO].of(List.empty[String])
      code2 <- push(out2)
      lines2 <- out2.get
    yield
      assertEquals(code2, ExitCode.Success, lines2.mkString("\n"))
      assert(lines2.exists(_.contains(s"already published as $head")), lines2.mkString("\n"))
      assert(!lines2.exists(_.contains("--parent")), lines2.mkString("\n"))
  }
