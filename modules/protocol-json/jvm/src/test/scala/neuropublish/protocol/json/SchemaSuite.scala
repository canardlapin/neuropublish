package neuropublish.protocol.json

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite

/** JVM: the JSON Schema stage and the single-source-of-truth rule for the schema documents. */
class SchemaSuite extends FunSuite:
  private val schemas =
    List("protocol/schemas", "../../protocol/schemas", "../../../protocol/schemas").map(Path.of(_))
      .find(Files.isDirectory(_)).getOrElse(fail("protocol/schemas not found"))

  test("the classpath schemas are the protocol/schemas files") {
    for name <- List("manifest", "workspace-state", "rendition-header") do
      val res = getClass.getClassLoader.getResourceAsStream(s"schemas/$name.schema.json")
      assert(res != null, s"schemas/$name.schema.json is not on the classpath")
      assertEquals(
        new String(res.readAllBytes(), "UTF-8"),
        Files.readString(schemas.resolve(s"$name.schema.json")),
        name
      )
  }

  private val base =
    """{"core":"0.1","title":"t","sensitivity":"group-level","assets":[],"extra":{"x":[1]}}"""
  private def json(s: String) = parse(s).fold(e => fail(e.message), identity)

  test("a minimal manifest with unknown fields passes the schema") {
    assertEquals(SchemaCheck.manifest(json(base)), Nil)
  }

  test("schema problems carry RFC 6901 pointers") {
    val bad = json(
      """{"core":"0.1","title":"t","sensitivity":"loud","assets":[{"id":"a","size":1,"mediaType":"x/y"}],
         "resultFields":[{"id":"f","estimand":"e","measure":"not an id","domain":"d","selection":{},
           "representations":[{"kind":"hologram","asset":"a"}],
           "publishedDisplay":{"threshold":{"mode":"sideways","min":1},"window":{"min":0,"max":1},"colormap":"c"}}]}"""
    )
    val ps = SchemaCheck.manifest(bad).map(_.pointer)
    assert(ps.contains("/sensitivity"), ps)
    assert(ps.contains("/assets/0/digest"), ps)
    assert(ps.contains("/resultFields/0/measure"), ps)
    assert(ps.contains("/resultFields/0/representations/0/kind"), ps)
    assert(ps.contains("/resultFields/0/publishedDisplay/threshold/mode"), ps)
  }

  test("closed core structures reject unknown members; open ones keep them") {
    val rep = json(base.replace(
      "\"assets\":[]",
      "\"assets\":[],\"underlays\":[{\"asset\":\"a\",\"domain\":\"d\",\"label\":\"l\",\"extra\":1}]"
    ))
    assertEquals(SchemaCheck.manifest(rep).map(_.pointer), List("/underlays/0"))
  }
