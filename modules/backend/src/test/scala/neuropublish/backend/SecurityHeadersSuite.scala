package neuropublish.backend

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Request, Response, Status}
import org.typelevel.ci.CIString

class SecurityHeadersSuite extends CatsEffectSuite:
  private val app = HttpApp[IO](_ => IO.pure(Response[IO](Status.Ok)))

  private def value(response: Response[IO], name: String): Option[String] =
    response.headers.headers.find(_.name == CIString(name)).map(_.value)

  test("browser policy is present; HSTS follows the public HTTPS origin"):
    for
      secure <- SecurityHeaders(app, https = true).run(Request[IO]())
      local <- SecurityHeaders(app, https = false).run(Request[IO]())
    yield
      assert(value(secure, "Content-Security-Policy").exists(_.contains("frame-ancestors 'none'")))
      assertEquals(value(secure, "X-Content-Type-Options"), Some("nosniff"))
      assertEquals(value(secure, "X-Frame-Options"), Some("DENY"))
      assert(value(secure, "Strict-Transport-Security").exists(_.contains("max-age=31536000")))
      assertEquals(value(local, "Strict-Transport-Security"), None)
