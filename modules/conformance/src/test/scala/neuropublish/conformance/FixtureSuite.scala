package neuropublish.conformance

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.ByteProfile

/** The hand-written reference bundle and the invalid-manifest suite. */
class FixtureSuite extends FunSuite:
  private val fixtures =
    List("fixtures", "modules/conformance/fixtures").map(Path.of(_)).find(Files.isDirectory(_))
      .getOrElse(fail("fixtures directory not found from " + Path.of("").toAbsolutePath))
  private def read(p: String) = Files.readAllBytes(fixtures.resolve(p))

  test("reference manifest is admitted and its digest matches java.security") {
    val bytes = read("reference/manifest.json")
    val ours = ByteProfile.admit(bytes).fold(vs => fail(vs.map(_.render).mkString("; ")), identity)
    val jdk = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      .map(b => f"${b & 0xff}%02x").mkString
    assertEquals(ours.hex, jdk)
    assertEquals(ours.hex, Files.readString(fixtures.resolve("reference/manifest.sha256")).trim)
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
