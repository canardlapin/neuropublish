package neuropublish.conformance

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{
  ByteProfile,
  FiniteIndexed,
  HardAssignment,
  Interpretation,
  Manifest,
  Problem,
  TrustedSchemas
}

/** Golden neutral parcel publication: an exact ordered finite field, its spatial hard assignment,
  * and a producer-authored pullback volume with a distinct derivation receipt.
  */
class ParcelFixtureSuite extends FunSuite:
  private val fixture =
    List("fixtures/parcel", "modules/conformance/fixtures/parcel").map(Path.of(_))
      .find(Files.isDirectory(_))
      .getOrElse(fail("fixtures/parcel not found from " + Path.of("").toAbsolutePath))
  private val manifestPath = fixture.resolve("manifest.json")
  private val expectedKeys = Vector(
    "schaefer2018-7networks-lh-visual-1",
    "schaefer2018-7networks-lh-default-1",
    "schaefer2018-7networks-rh-visual-1",
    "schaefer2018-7networks-rh-default-1"
  )

  private lazy val parsed = Manifest.parse(Files.readAllBytes(manifestPath)).fold(
    ps => fail(Problem.render(ps)),
    identity
  )
  private def manifest = parsed._2
  private def assetPath(hex: String): Path =
    fixture.resolve(s"assets/sha256/${hex.take(2)}/$hex")
  private def bytes(id: String): Array[Byte] =
    val asset = manifest.asset(id).getOrElse(fail(s"missing asset $id"))
    Files.readAllBytes(assetPath(asset.digest.hex))

  test("packed manifest and every parcel asset retain exact byte identities") {
    val manifestBytes = Files.readAllBytes(manifestPath)
    val admitted = ByteProfile.admit(manifestBytes)
      .fold(vs => fail(vs.map(_.render).mkString("; ")), identity)
    val expected = Files.readString(fixture.resolve("manifest.sha256")).trim
    assertEquals(admitted.hex, expected)
    assertEquals(parsed._1.hex, expected)
    assertEquals(manifest.assets.length, 4)

    val declared = manifest.assets.map { asset =>
      val path = assetPath(asset.digest.hex)
      assert(Files.isRegularFile(path), s"missing packed asset ${asset.id}: $path")
      val file = Files.readAllBytes(path)
      assertEquals(file.length.toLong, asset.size, asset.id)
      assertEquals(Sha256.of(file), asset.digest, asset.id)
      path.toAbsolutePath.normalize
    }.toSet
    val stream = Files.walk(fixture.resolve("assets"))
    val present = try stream.iterator.asScala.filter(Files.isRegularFile(_))
        .map(_.toAbsolutePath.normalize).toSet
    finally stream.close()
    assertEquals(present, declared)
  }

  test("finite identity is the exact ordered Schaefer key vector") {
    val domain = manifest.domain("schaefer-ordered").getOrElse(fail("missing finite domain"))
    TrustedSchemas.interpret("/domains/1/descriptor", domain.descriptor) match
      case Interpretation.Understood(record, _) =>
        assertEquals(record.schema.id, TrustedSchemas.FiniteIndexedV1.id)
      case other => fail(s"finite descriptor was not trusted: $other")
    val payload = FiniteIndexed.readPayload(
      "/domains/1/descriptor/payload",
      domain.descriptor.payload
    ).fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(payload.elementKeys, expectedKeys)
    val preimage = FiniteIndexed.preimage(
      domain.descriptor.schema.id,
      domain.descriptor.schema.version,
      payload
    )
    assert(java.util.Arrays.equals(bytes(payload.keysAsset), preimage))
    assertEquals(
      domain.key.flatMap(_.hcursor.get[String]("structuralFingerprint").toOption),
      Some(Sha256.of(preimage).render)
    )
  }

  test("hard assignment bytes prove bounds and complete target coverage") {
    val mapping = manifest.domainMapping("schaefer-volume").getOrElse(fail("missing mapping"))
    assertEquals(mapping.source, "mni-toy")
    assertEquals(mapping.target, "schaefer-ordered")
    assertEquals(HardAssignment.checkDeclaration("/domainMappings/0", mapping, manifest), Nil)
    val payload = HardAssignment.readPayload(
      "/domainMappings/0/descriptor/payload",
      mapping.descriptor.payload
    ).fold(ps => fail(Problem.render(ps)), identity)
    val checked = HardAssignment.checkBytes(
      "/domainMappings/0",
      payload,
      8,
      expectedKeys,
      bytes(payload.asset)
    ).fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(checked.ordinals, Vector(0, 0, 1, 1, 2, 2, 3, 3))
    assertEquals(checked.emptyParcels, Vector.empty)
    assert(checked.surjective)
  }

  test("parcel values and their spatial pullback carry separate, closed receipts") {
    val values = ByteBuffer.wrap(bytes("parcel-values")).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(Vector.fill(4)(values.getFloat()), Vector(-0.75f, 0.25f, 1.5f, 2.25f))

    val field = manifest.resultFields.find(_.id == "parcel-effect-field")
      .getOrElse(fail("missing parcel field"))
    assertEquals(field.domain, "schaefer-ordered")
    assertEquals(field.representations.map(_.kind), List("table", "volume"))
    val volume = field.representations.find(_.kind == "volume").get
    assertEquals(volume.domain, Some("mni-toy"))
    assertEquals(volume.mapping, Some("schaefer-volume"))
    assertEquals(volume.derivation, Some("pullback-parcel-values"))

    val activities = manifest.raw.hcursor.downField("provenance").downField("activities")
      .as[List[io.circe.Json]].toOption.getOrElse(fail("missing activities"))
    val ids = activities.flatMap(_.hcursor.get[String]("id").toOption)
    assertEquals(ids, List("construct-schaefer-assignment", "pullback-parcel-values"))
    val pullback = activities(1).hcursor.downField("payload")
    assertEquals(pullback.get[String]("sourceField"), Right(field.id))
    assertEquals(pullback.get[String]("mapping"), Right("schaefer-volume"))
    assertEquals(
      pullback.get[String]("outputDigest"),
      Right(manifest.asset("parcel-pullback").get.digest.render)
    )
  }

  test("reordered or foreign Schaefer keys are rejected against the pinned identity") {
    val original = Files.readString(manifestPath)
    val reordered = original
      .replace(expectedKeys(0), "__NP_KEY_SWAP__")
      .replace(expectedKeys(1), expectedKeys(0))
      .replace("__NP_KEY_SWAP__", expectedKeys(1))
    val reorderProblems = Manifest.parse(reordered.getBytes("UTF-8")).left.toOption
      .getOrElse(fail("reordered keys were admitted"))
    assert(
      reorderProblems.exists(_.pointer == "/domains/1/key/structuralFingerprint"),
      reorderProblems
    )

    val foreign = original.replace(
      expectedKeys.head,
      "schaefer2018-17networks-lh-visual-1"
    )
    val foreignProblems = Manifest.parse(foreign.getBytes("UTF-8")).left.toOption
      .getOrElse(fail("foreign atlas variant was admitted"))
    assert(
      foreignProblems.exists(_.pointer == "/domains/1/key/structuralFingerprint"),
      foreignProblems
    )
  }

  test("a finite cross-domain representation needs both mapping and derivation receipts") {
    val original = Files.readString(manifestPath)
    val withoutMapping = original.replace(
      "          \"mapping\" : \"schaefer-volume\",\n          \"derivation\" : \"pullback-parcel-values\"",
      "          \"derivation\" : \"pullback-parcel-values\""
    )
    val mappingProblems = Manifest.parse(withoutMapping.getBytes("UTF-8")).left.toOption
      .getOrElse(fail("finite pullback without a mapping was admitted"))
    assertEquals(
      mappingProblems.map(_.pointer),
      List("/resultFields/0/representations/1/mapping")
    )

    val withoutDerivation = original.replace(
      ",\n          \"derivation\" : \"pullback-parcel-values\"",
      ""
    )
    val derivationProblems = Manifest.parse(withoutDerivation.getBytes("UTF-8")).left.toOption
      .getOrElse(fail("finite pullback without a derivation was admitted"))
    assertEquals(
      derivationProblems.map(_.pointer),
      List("/resultFields/0/representations/1/derivation")
    )
  }
