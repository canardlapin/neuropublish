package neuropublish.conformance

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import io.circe.Json
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import neuropublish.protocol.{Measures, Sha256}
import neuropublish.protocol.json.{
  ByteProfile,
  Interpretation,
  Manifest,
  OpenRecord,
  Problem,
  TrustedSchemas
}

/** Cross-repository contract for the producer-owned fmrigds mapping.
  *
  * The fixture is a pinned real occurrence, not a hand-written approximation. Tests deliberately
  * assert stable scientific and privacy semantics rather than dynamic occurrence ids or clocks.
  */
class FmrigdsFixtureSuite extends FunSuite:
  private val fixture =
    List("fixtures/fmrigds", "modules/conformance/fixtures/fmrigds").map(Path.of(_))
      .find(Files.isDirectory(_))
      .getOrElse(fail("fixtures/fmrigds not found from " + Path.of("").toAbsolutePath))
  private val manifestPath = fixture.resolve("manifest.json")

  private lazy val parsed =
    Manifest.parse(Files.readAllBytes(manifestPath)).fold(
      ps => fail(Problem.render(ps)),
      identity
    )
  private def manifest = parsed._2

  private def required[A](result: Either[io.circe.DecodingFailure, A]): A =
    result.fold(e => fail(e.message), identity)

  private def arrayAt(json: Json, field: String): Vector[Json] =
    json.hcursor.downField(field).focus.flatMap(_.asArray)
      .getOrElse(fail(s"missing array '$field'"))

  private def provenance: Json =
    manifest.raw.hcursor.downField("provenance").focus.getOrElse(fail("missing provenance"))

  private def assetPath(hex: String): Path =
    fixture.resolve(s"assets/sha256/${hex.take(2)}/$hex")

  test("packed manifest and every declared asset retain their exact byte identities") {
    val bytes = Files.readAllBytes(manifestPath)
    val byteDigest = ByteProfile.admit(bytes)
      .fold(vs => fail(vs.map(_.render).mkString("; ")), identity)
    val expected = Files.readString(fixture.resolve("manifest.sha256")).trim
    assertEquals(byteDigest.hex, expected)
    assertEquals(parsed._1.hex, expected)
    assertEquals(manifest.assets.length, 10)

    val declared = manifest.assets.map { asset =>
      val path = assetPath(asset.digest.hex)
      assert(Files.isRegularFile(path), s"missing packed asset ${asset.id}: $path")
      val file = Files.readAllBytes(path)
      assertEquals(file.length.toLong, asset.size, asset.id)
      assertEquals(Sha256.of(file).render, asset.digest.render, asset.id)
      path.toAbsolutePath.normalize
    }.toSet

    val stream = Files.walk(fixture.resolve("assets"))
    val present = try stream.iterator.asScala.filter(Files.isRegularFile(_))
        .map(_.toAbsolutePath.normalize).toSet
    finally stream.close()
    assertEquals(present, declared)
  }

  test("fmrigds owns the reduction and measure projection without granting unknown semantics") {
    val analysis = manifest.analyses.headOption.getOrElse(fail("missing fmrigds analysis"))
    assertEquals(manifest.analyses.length, 1)
    assertEquals(analysis.sampleSize, Some(6))
    assertEquals(analysis.estimands.map(_.label), List("faces", "places"))

    val receipt = analysis.method.getOrElse(fail("missing reduction receipt")).hcursor
      .downField("payload")
    assertEquals(required(receipt.get[String]("status")), "portable")
    assertEquals(required(receipt.get[String]("reducerId")), "meta:re")
    assertEquals(required(receipt.get[Int]("nInputSubjects")), 6)
    assertEquals(
      required(receipt.get[List[String]]("inputContrasts")),
      List("faces", "places")
    )
    assertEquals(
      required(receipt.downField("weight").get[String]("mode")),
      "1/var"
    )

    val expectedMeasures = List(
      "org.neuropublish.measure/effect",
      "org.neuropublish.measure/standard-error",
      "org.neuropublish.measure/z-statistic",
      "org.bbuchsbaum.fmrigds.measure/between-study-heterogeneity-variance",
      "org.bbuchsbaum.fmrigds.measure/effective-sample-size"
    )
    analysis.estimands.foreach { estimand =>
      assertEquals(manifest.orderedFields(estimand.id).map(_.measure), expectedMeasures)
    }

    expectedMeasures.take(3).foreach(id => assert(Measures.lookup(id).nonEmpty, id))
    expectedMeasures.drop(3).foreach(id => assertEquals(Measures.lookup(id), None, id))
    val tau = manifest.resultFields.filter(_.measure == expectedMeasures(3))
    assertEquals(tau.map(_.label).distinct, List(Some("Between-study heterogeneity (τ²)")))
    assertEquals(
      manifest.warnings.map(w => (w.id, w.message)),
      List((
        "synthetic-sample-labels",
        "Sample labels were generated and do not carry anatomical identity."
      ))
    )
  }

  test("verified private source receipts remain useful without leaking source locators") {
    val entities = arrayAt(provenance, "entities")
    assertEquals(entities.length, 12)

    val rolesAndPairs = entities.map { entity =>
      val cursor = entity.hcursor
      val record = required(entity.as[OpenRecord])
      assertEquals(
        TrustedSchemas.interpret("/provenance/entities/source", record),
        Interpretation.Unsupported(record)
      )
      assertEquals(required(cursor.get[String]("label")), "fmrigds file")
      assertEquals(required(cursor.get[Boolean]("hosted")), false)
      assertEquals(
        required(cursor.downField("schema").get[String]("id")),
        "org.bbuchsbaum.fmrigds/source-entity"
      )
      val payload = cursor.downField("payload")
      assertEquals(required(payload.get[String]("identityStatus")), "verified")
      assertEquals(required(payload.get[String]("kind")), "file")
      assertEquals(required(payload.get[Long]("byteSize")), 416L)
      assertEquals(required(payload.downField("digest").get[String]("algorithm")), "sha256")
      val digest = required(payload.downField("digest").get[String]("value"))
      assert(digest.matches("[0-9a-f]{64}"), digest)
      val keys = payload.focus.flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)
      assertEquals(
        keys.intersect(Set(
          "path",
          "localPath",
          "basename",
          "locator",
          "uri",
          "url",
          "subject",
          "subjectId"
        )),
        Set.empty[String]
      )
      val role = arrayAt(payload.focus.getOrElse(fail("missing source payload")), "roles")
        .headOption.getOrElse(fail("source entity has no role")).hcursor
      (required(role.get[String]("role")), required(role.get[Int]("pair")))
    }
    assertEquals(
      rolesAndPairs,
      (1 to 6).map("beta" -> _).toVector ++
        (1 to 6).map("standard-error" -> _).toVector
    )

    val text = Files.readString(manifestPath, StandardCharsets.UTF_8)
    List("/Users/", "/private/", "input-01-beta.nii", "input-01-se.nii")
      .foreach(secret => assert(!text.contains(secret), s"manifest leaked '$secret'"))
    assert(!text.matches("(?s).*input-[0-9]{2}-(beta|se)\\.nii.*"))

  }

  test("the portable provenance graph is closed and traces every source and result field") {
    val entities = arrayAt(provenance, "entities")
    val activities = arrayAt(provenance, "activities")
    val edges = arrayAt(provenance, "edges")
    assertEquals(activities.length, 1)
    val entityIds = entities.map(e => required(e.hcursor.get[String]("id"))).toSet
    val activityId = required(activities.head.hcursor.get[String]("id"))
    val fieldIds = manifest.resultFields.map(_.id).toSet

    val activity = activities.head.hcursor
    assertEquals(
      required(activity.downField("schema").get[String]("id")),
      "org.bbuchsbaum.fmrigds/activity"
    )
    assertEquals(
      required(activity.downField("payload").get[String]("semanticId")),
      "org.bbuchsbaum.fmrigds.operation/reduce"
    )
    assertEquals(
      required(activity.downField("payload").downField("params").get[Int]("nInputSubjects")),
      6
    )

    val triples = edges.map { edge =>
      val cursor = edge.hcursor
      (
        required(cursor.get[String]("from")),
        required(cursor.get[String]("to")),
        required(cursor.get[String]("role"))
      )
    }
    val used = triples.filter(_._3 == "used")
    val generated = triples.filter(_._3 == "generated")
    assertEquals(used.length, 12)
    assertEquals(generated.length, 10)
    assertEquals(used.map(_._1).toSet, entityIds)
    assert(used.forall(_._2 == activityId))
    assert(generated.forall(_._1 == activityId))
    assertEquals(generated.map(_._2).toSet, fieldIds)
    assertEquals(triples.map(_._3).toSet, Set("used", "generated"))
  }
