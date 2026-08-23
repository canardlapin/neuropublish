package neuropublish.backend

import fs2.io.file.Path
import io.circe.Json
import munit.FunSuite
import neuropublish.protocol.json.Manifest

/** The share response is a pure allow-list projection of the manifest: nothing from the publication
  * record that the presentation does not render may appear in it.
  */
class SharedProjectionSuite extends FunSuite:
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val reference: Json =
    io.circe.parser.parse(
      java.nio.file.Files.readString((fixtures / "reference" / "manifest.json").toNioPath)
    ).fold(throw _, identity)
  private val shared = SharedProjection.of(reference)
  private val text = shared.noSpaces

  test("provenance, subjects, method payloads and open records never appear") {
    // the fixture's receipts carry per-subject payloads
    assert(reference.noSpaces.contains("\"subject\":\"01\""))
    for
      needle <- List(
        "provenance",
        "\"subject\"",
        "first-level-01",
        "first-level-02",
        "temporalNoise",
        "cosine-128s",
        "\"method\"",
        "inverse-variance",
        "fmrigds 0.7.2",
        "\"sensitivity\"",
        "\"axes\"",
        "\"catalog\"",
        "templateflow"
      )
    do assert(!text.contains(needle), s"shared manifest leaks '$needle'")
  }

  test("what the presentation renders is kept, and it still decodes as a Manifest") {
    val keys = shared.asObject.get.keys.toSet
    assertEquals(
      keys,
      Set(
        "core",
        "title",
        "synopsis",
        "warnings",
        "resultFields",
        "underlays",
        "domains",
        "analyses",
        "assets"
      )
    )
    val c = shared.hcursor
    assertEquals(
      c.downField("analyses").downArray.keys.map(_.toSet),
      Some(Set("id", "label", "estimands", "sampleSize"))
    )
    assertEquals(
      c.downField("assets").downArray.keys.map(_.toSet),
      Some(Set("id", "digest", "size", "mediaType"))
    )
    assertEquals(c.downField("assets").as[List[Json]].map(_.length), Right(5))
    val m = shared.as[Manifest].fold(e => fail(e.getMessage), identity)
    assertEquals(
      m.resultFields.map(_.id),
      List("speech-effect", "speech-se", "speech-t", "speech-z")
    )
    assertEquals(m.analyses.map(_.method), List(None))
    assertEquals(m.analyses.flatMap(_.sampleSize), List(26))
    assertEquals(Manifest.referenceClosure(m), Right(()))
  }

  test("share policy: only group-level results may be linked") {
    assertEquals(SharedProjection.shareable(reference), Right(()))
    val subject = reference.mapObject(_.add("sensitivity", Json.fromString("subject-level")))
    assert(SharedProjection.shareable(subject).isLeft)
    val none = reference.mapObject(_.remove("sensitivity"))
    assert(SharedProjection.shareable(none).isLeft)
  }
