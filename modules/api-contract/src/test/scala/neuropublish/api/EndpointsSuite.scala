package neuropublish.api

import munit.FunSuite

class EndpointsSuite extends FunSuite:
  test("health endpoint path and codec round trip") {
    assertEquals(Endpoints.health.showShort, "GET /api/v1/health")
    val h = Health("ok", "0.1", "neuropublish-backend")
    val json = io.circe.syntax.EncoderOps(h).asJson.noSpaces
    assertEquals(io.circe.parser.decode[Health](json), Right(h))
  }
