package neuropublish.conformance

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import io.circe.HCursor
import io.circe.parser.parse
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.ByteProfile

/** The hand-written reference bundle and the invalid-manifest suite. */
class FixtureSuite extends FunSuite:
  private val VolumeGridMagic = "NPUDOM1\u0000".getBytes(StandardCharsets.US_ASCII)
  private val fixtures =
    List("fixtures", "modules/conformance/fixtures").map(Path.of(_)).find(Files.isDirectory(_))
      .getOrElse(fail("fixtures directory not found from " + Path.of("").toAbsolutePath))
  private def read(p: String) = Files.readAllBytes(fixtures.resolve(p))

  private def required[A](value: Either[io.circe.DecodingFailure, A]): A =
    value.fold(e => fail(e.message), identity)

  private def putString(buffer: ByteBuffer, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    buffer.putInt(bytes.length)
    val _ = buffer.put(bytes)

  private def volumeGridPreimage(
      descriptorId: String,
      descriptorVersion: String,
      space: String,
      coordinateConvention: String,
      spatialUnit: String,
      ordinalLayout: String,
      shape: Vector[Int],
      affine: Vector[Double]
  ): Array[Byte] =
    val strings =
      Vector(
        descriptorId,
        descriptorVersion,
        space,
        coordinateConvention,
        spatialUnit,
        ordinalLayout
      )
    val stringBytes = strings.map(_.getBytes(StandardCharsets.UTF_8))
    val size = VolumeGridMagic.length + stringBytes.map(bytes => 4 + bytes.length).sum + 3 * 4 +
      16 * 8
    val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(VolumeGridMagic)
    strings.foreach(value => putString(buffer, value))
    shape.foreach(buffer.putInt)
    affine.foreach(value => buffer.putDouble(if value == 0.0 then 0.0 else value))
    buffer.array()

  private def referenceDomain: HCursor =
    val manifest = parse(new String(read("reference/manifest.json"), StandardCharsets.UTF_8))
      .fold(e => fail(e.message), identity)
    manifest.hcursor.downField("domains").downArray.success
      .getOrElse(fail("reference manifest has no domain"))

  test("reference manifest is admitted and its digest matches java.security") {
    val bytes = read("reference/manifest.json")
    val ours = ByteProfile.admit(bytes).fold(vs => fail(vs.map(_.render).mkString("; ")), identity)
    val jdk = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      .map(b => f"${b & 0xff}%02x").mkString
    assertEquals(ours.hex, jdk)
    assertEquals(ours.hex, Files.readString(fixtures.resolve("reference/manifest.sha256")).trim)
  }

  test("reference volume domain uses the open descriptor and recomputable exact key") {
    val domain = referenceDomain
    assertEquals(domain.downField("kind").focus, None)

    val key = domain.downField("key")
    val descriptor = domain.downField("descriptor")
    val schema = descriptor.downField("schema")
    val payload = descriptor.downField("payload")
    assertEquals(key.downField("descriptor").focus, schema.focus)

    val descriptorId = required(schema.get[String]("id"))
    val descriptorVersion = required(schema.get[String]("version"))
    val shape = required(payload.get[Vector[Int]]("shape"))
    val affineRows = required(payload.get[Vector[Vector[Double]]]("affine"))
    val affine = affineRows.flatten
    assertEquals(shape.length, 3)
    assertEquals(affineRows.map(_.length), Vector.fill(4)(4))
    assertEquals(required(key.get[Long]("size")), shape.map(_.toLong).product)

    val preimage = volumeGridPreimage(
      descriptorId,
      descriptorVersion,
      required(payload.get[String]("space")),
      required(payload.get[String]("coordinateConvention")),
      required(payload.get[String]("spatialUnit")),
      required(payload.get[String]("ordinalLayout")),
      shape,
      affine
    )
    assertEquals(
      required(key.get[String]("structuralFingerprint")),
      Sha256.of(preimage).render
    )

    val schemaBytes = read("reference/schemas/volume-grid-v1.schema.json")
    assertEquals(required(schema.get[String]("digest")), Sha256.of(schemaBytes).render)
  }

  test("every invalid fixture is rejected with the documented reason") {
    val dir = fixtures.resolve("invalid")
    val cases = Files.list(dir).toList.asScala.filter(_.toString.endsWith(".json")).toList
    assert(cases.nonEmpty)
    cases.foreach { p =>
      val expect = Files.readString(Path.of(p.toString.stripSuffix(".json") + ".expect")).trim
      ByteProfile.admit(Files.readAllBytes(p)) match
        case Right(_) => fail(s"${p.getFileName} was admitted")
        case Left(vs) => assert(
            vs.exists(_.message.contains(expect)),
            s"${p.getFileName}: expected '$expect' in ${vs.map(_.render)}"
          )
    }
  }
