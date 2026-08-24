package neuropublish.backend

import munit.FunSuite

class S3ConfigSuite extends FunSuite:
  test("a public endpoint affects presigned URLs without changing the service endpoint"):
    val config = ObjectStore.S3Config.fromEnv(Map(
      "NP_S3_BUCKET" -> "results",
      "NP_S3_ENDPOINT" -> "http://minio:9000",
      "NP_S3_PUBLIC_ENDPOINT" -> "http://127.0.0.1:9000",
      "NP_S3_PATH_STYLE" -> "true"
    )).get

    assertEquals(config.endpoint, Some("http://minio:9000"))
    assertEquals(config.presigningEndpoint, Some("http://127.0.0.1:9000"))
    assert(config.pathStyle)

  test("the service endpoint remains the presigning default"):
    val config = ObjectStore.S3Config.fromEnv(Map(
      "NP_S3_BUCKET" -> "results",
      "NP_S3_ENDPOINT" -> "https://objects.example.org"
    )).get

    assertEquals(config.presigningEndpoint, config.endpoint)
