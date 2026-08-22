package neuropublish.backend

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status}
import org.http4s.implicits.*

class RoutesSuite extends CatsEffectSuite:
  test("GET /api/v1/health") {
    Routes.routes.orNotFound.run(Request[IO](Method.GET, uri"/api/v1/health")).flatMap { r =>
      assertEquals(r.status, Status.Ok)
      r.as[String].map(body => assert(body.contains("\"protocol\":\"0.1\""), body))
    }
  }
  test("OpenAPI document lists the health path") {
    assert(Routes.openApiYaml.contains("/api/v1/health"))
  }
