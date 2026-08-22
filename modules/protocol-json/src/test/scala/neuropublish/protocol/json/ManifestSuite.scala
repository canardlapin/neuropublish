package neuropublish.protocol.json

import munit.FunSuite

class ManifestSuite extends FunSuite:
  private val text =
    """{
    "core": "0.1", "title": "t",
    "assets": [{"id": "a", "digest": "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "size": 0, "mediaType": "application/x-nifti"}],
    "resultFields": [{"id": "f", "estimand": "e", "measure": "org.neuropublish.measure/t-statistic", "domain": "d", "selection": {}, "representations": [{"kind": "volume", "asset": "a"}], "unknownExtension": {"x": 1}}],
    "underlays": [{"asset": "a", "domain": "d", "label": "T1"}],
    "somethingNew": {"kept": true}
  }"""

  test("decodes the acted-on projection and retains the rest") {
    val (digest, m) = Manifest.parse(text.getBytes("UTF-8")).fold(fail(_), identity)
    assertEquals(m.assets.map(_.id), List("a"))
    assertEquals(m.volumeAssetIds, List("a"))
    assertEquals(m.raw.hcursor.downField("somethingNew").downField("kept").as[Boolean], Right(true))
    assertEquals(digest.hex.length, 64)
  }
  test("rejects dangling asset references") {
    val bad = text.replace("\"asset\": \"a\"}]", "\"asset\": \"zz\"}]")
    assert(Manifest.parse(bad.getBytes("UTF-8")).left.exists(_.contains("zz")))
  }
