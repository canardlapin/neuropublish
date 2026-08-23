package neuropublish.protocol.json

import munit.FunSuite

class ManifestSuite extends FunSuite:
  private val text =
    """{
    "core": "0.1", "title": "t", "sensitivity": "group-level",
    "domains": [{"id": "d", "descriptor": {"schema": {"id": "org.example.lab/grid", "version": "1"}, "payload": {}}}],
    "assets": [{"id": "a", "digest": "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "size": 0, "mediaType": "application/x-nifti"}],
    "analyses": [{"id": "an", "label": "A", "estimands": [{"id": "e", "label": "E", "order": 1}, {"id": "e2", "label": "E2"}]}],
    "resultFields": [{"id": "f", "estimand": "e", "measure": "org.neuropublish.measure/t-statistic", "domain": "d", "selection": {}, "representations": [{"kind": "volume", "asset": "a"}], "unknownExtension": {"x": 1}}],
    "underlays": [{"asset": "a", "domain": "d", "label": "T1"}],
    "somethingNew": {"kept": true}
  }"""
  private def parse(s: String) = Manifest.parse(s.getBytes("UTF-8"))
  private def problems(s: String): List[Problem] =
    parse(s).fold(identity, _ => fail("expected problems"))

  test("decodes the acted-on projection and retains the rest") {
    val (digest, m) = parse(text).fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(m.assets.map(_.id), List("a"))
    assertEquals(m.volumeAssetIds, List("a"))
    assertEquals(m.raw.hcursor.downField("somethingNew").downField("kept").as[Boolean], Right(true))
    assertEquals(digest.hex.length, 64)
    assertEquals(
      m.openRecords.map(_._3).collect { case Interpretation.Unsupported(r) => r.schema.id },
      List("org.example.lab/grid")
    )
  }
  test("rejects dangling asset references with a pointer") {
    val bad = text.replace("\"asset\": \"a\"}]", "\"asset\": \"zz\"}]")
    assertEquals(problems(bad).map(_.pointer), List("/resultFields/0/representations/0/asset"))
  }
  test("problems accumulate across checks") {
    val bad = text
      .replace("\"sensitivity\": \"group-level\"", "\"sensitivity\": \"secret\"")
      .replace("\"estimand\": \"e\"", "\"estimand\": \"nope\"")
      .replace(
        "\"id\": \"e2\", \"label\": \"E2\"",
        "\"id\": \"e2\", \"label\": \"E2\", \"order\": 1"
      )
    val ps = problems(bad).map(_.pointer)
    assert(ps.contains("/sensitivity"), ps)
    assert(ps.contains("/resultFields/0/estimand"), ps)
    assert(ps.contains("/analyses/0/estimands/1/order"), ps)
  }
  test("a trusted-namespace record with a wrong digest is rejected, an unknown one retained") {
    val trusted = text.replace(
      "\"id\": \"org.example.lab/grid\", \"version\": \"1\"",
      "\"id\": \"org.neuropublish.domain/volume-grid\", \"version\": \"1.0\", \"digest\": \"sha256:" +
        "0" * 64 + "\""
    )
    assertEquals(problems(trusted).map(_.pointer), List("/domains/0/descriptor/schema/digest"))
  }
  test("core 0.0 is migrated (description → synopsis) and stamped; 0.2 is read; 1.0 is not") {
    val old = text.replace(
      "\"core\": \"0.1\", \"title\": \"t\"",
      "\"core\": \"0.0\", \"title\": \"t\", \"description\": \"s\""
    )
    val (_, m) = parse(old).fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(m.core, "0.1")
    assertEquals(m.synopsis, Some("s"))
    assertEquals(m.migratedFrom, Some("0.0"))
    assert(m.raw.hcursor.downField("description").failed)
    assert(parse(text.replace("\"core\": \"0.1\"", "\"core\": \"0.2\"")).isRight)
    assertEquals(
      problems(text.replace("\"core\": \"0.1\"", "\"core\": \"1.0\"")).map(_.pointer),
      List("/core")
    )
  }
  test("byte-profile violations are problems too; duplicate keys get a pointer") {
    val dup = text.replace(
      "\"somethingNew\": {\"kept\": true}",
      "\"somethingNew\": {\"kept\": true, \"kept\": 1}"
    )
    assertEquals(problems(dup).map(_.pointer), List("/somethingNew"))
  }
  test("JSON pointer escaping and decoding-failure paths") {
    assertEquals(JsonPointer.of(List("a/b", "m~n", "0")), "/a~1b/m~0n/0")
    assertEquals(JsonPointer.fromDotPath(".assets[0].digest"), "/assets/0/digest")
    val noDigest = text.replace(
      "\"digest\": \"sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\", ",
      ""
    )
    assert(problems(noDigest).exists(_.pointer == "/assets/0/digest"))
  }
