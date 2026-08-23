package neuropublish.npub

import cats.effect.{ExitCode, IO, Ref}
import fs2.io.file.Files
import munit.CatsEffectSuite
import neuropublish.api.*
import org.http4s.HttpApp
import org.http4s.client.Client
import sttp.tapir.server.http4s.Http4sServerInterpreter

/** Device flow against an in-process stub of the Stage 4 contract. */
class DeviceFlowSuite extends CatsEffectSuite:
  private val tmp = ResourceFunFixture(Files[IO].tempDirectory)
  private val user = User("u1", "ana@example.org", "Ana")

  /** Stub: `pending` for the first `pendingPolls` polls, then the given terminal status. */
  private def stub(pendingPolls: Int, terminal: DeviceToken): IO[(HttpApp[IO], Ref[IO, Int])] =
    Ref[IO].of(0).map { polls =>
      val start = Stage4.deviceStart.serverLogicSuccess[IO](_ =>
        IO.pure(DeviceCodes(
          deviceCode = "dev-abc",
          userCode = "WXYZ-1234",
          verificationUri = "http://np.test/device",
          verificationUriComplete = "http://np.test/device?code=WXYZ-1234",
          expiresIn = 600,
          interval = 0
        ))
      )
      val poll = Stage4.devicePoll.serverLogic[IO] { p =>
        if p.deviceCode != "dev-abc" then IO.pure(Left(ApiError("bad_request", "unknown code")))
        else
          polls.updateAndGet(_ + 1).map(n =>
            Right(if n <= pendingPolls then DeviceToken("pending", None, None, None) else terminal)
          )
      }
      val me = Stage4.me.serverSecurityLogic[String, IO] { case (bearer, _) =>
        IO.pure(bearer.filter(_ == "user-token").toRight(ApiError("unauthorized", "no")))
      }.serverLogicSuccess(_ => _ => IO.pure(Me(user, List(Membership("rotman", "owner")))))
      (Http4sServerInterpreter[IO]().toRoutes(List(start, poll, me)).orNotFound, polls)
    }

  private def capture: IO[(Ref[IO, Vector[String]], String => IO[Unit])] =
    Ref[IO].of(Vector.empty[String]).map(r => (r, s => r.update(_ :+ s)))

  tmp.test("pending twice, then granted: prints URL and code, stores token") { dir =>
    val server = "http://np.test:8080"
    for
      (app, polls) <-
        stub(2, DeviceToken("granted", Some("user-token"), Some("bearer"), Some(user)))
      (lines, out) <- capture
      code <- Login.run(new Api(Client.fromHttpApp(app), server), dir, out)
      printed <- lines.get
      n <- polls.get
      creds <- Credentials.load(dir)
    yield
      assertEquals(code, ExitCode.Success)
      assertEquals(
        printed,
        Vector(
          "Open  http://np.test/device?code=WXYZ-1234",
          "Code  WXYZ-1234",
          "Waiting for approval…",
          "Signed in as ana@example.org"
        )
      )
      assertEquals(n, 3)
      assertEquals(creds.get(server), Some(ServerEntry("user-token", "ana@example.org")))
      assert(!printed.exists(_.contains("user-token")), "token must never be printed")
  }

  tmp.test("granted without user falls back to auth/me") { dir =>
    for
      (app, _) <- stub(0, DeviceToken("granted", Some("user-token"), Some("bearer"), None))
      (lines, out) <- capture
      code <- Login.run(new Api(Client.fromHttpApp(app), "http://np.test"), dir, out)
      printed <- lines.get
    yield
      assertEquals(code, ExitCode.Success)
      assertEquals(printed.last, "Signed in as ana@example.org")
  }

  tmp.test("denied exits 1 with one line and stores nothing") { dir =>
    for
      (app, _) <- stub(1, DeviceToken("denied", None, None, None))
      (lines, out) <- capture
      code <- Login.run(new Api(Client.fromHttpApp(app), "http://np.test"), dir, out)
      printed <- lines.get
      exists <- Files[IO].exists(Credentials.file(dir))
    yield
      assertEquals(code, ExitCode.Error)
      assertEquals(printed.last, "error  login denied")
      assert(!exists)
  }

  tmp.test("expired status exits 1") { dir =>
    for
      (app, _) <- stub(0, DeviceToken("expired", None, None, None))
      (lines, out) <- capture
      code <- Login.run(new Api(Client.fromHttpApp(app), "http://np.test"), dir, out)
      printed <- lines.get
    yield
      assertEquals(code, ExitCode.Error)
      assert(printed.last.startsWith("error  login code expired"))
  }

  tmp.test("unreachable server exits 1") { dir =>
    val failing = Client[IO](_ =>
      cats.effect.Resource.eval(IO.raiseError(new java.net.ConnectException("refused")))
    )
    for
      (lines, out) <- capture
      code <- Login.run(new Api(failing, "http://np.test:1"), dir, out)
      printed <- lines.get
    yield
      assertEquals(code, ExitCode.Error)
      assertEquals(printed, Vector("error  cannot reach http://np.test:1"))
  }

  tmp.test("whoami and logout use the stored entry") { dir =>
    val server = "http://np.test"
    for
      (app, _) <- stub(0, DeviceToken("granted", Some("user-token"), None, Some(user)))
      (lines, out) <- capture
      _ <-
        Credentials.save(dir, CredentialsFile().put(server, ServerEntry("user-token", user.email)))
      who <- Login.whoami(new Api(Client.fromHttpApp(app), server), "user-token", out)
      bye <- Login.logout(server, dir, out)
      after <- Credentials.load(dir)
      printed <- lines.get
    yield
      assertEquals(who, ExitCode.Success)
      assertEquals(bye, ExitCode.Success)
      assertEquals(printed.take(2), Vector("ana@example.org  (Ana)", "  rotman  owner"))
      assertEquals(after.get(server), None)
  }
