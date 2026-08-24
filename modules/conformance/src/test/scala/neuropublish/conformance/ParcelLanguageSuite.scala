package neuropublish.conformance

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.sys.process.*

/** Cross-language binary oracle: Scala's packed fixture, the R client, and a standalone Julia
  * producer must emit the same finite-domain and hard-assignment bytes.
  */
class ParcelLanguageSuite extends FunSuite:
  private val scripts = List("julia", "modules/conformance/julia").map(Path.of(_))
    .find(Files.isDirectory(_)).getOrElse(fail("modules/conformance/julia not found"))
  private val rPackage = List("clients/r/neuropublish", "../../clients/r/neuropublish")
    .map(Path.of(_)).find(Files.isDirectory(_)).getOrElse(fail("R client not found"))
  private val fixture = List("fixtures/parcel", "modules/conformance/fixtures/parcel")
    .map(Path.of(_)).find(Files.isDirectory(_)).getOrElse(fail("parcel fixture not found"))
  private val toolsRequired =
    sys.env.get("NP_TEST_REQUIRE_TOOLS").exists(v => v == "1" || v.equalsIgnoreCase("true")) ||
      sys.env.get("CI").exists(_.equalsIgnoreCase("true"))

  private def onPath(tool: String): Boolean =
    try Process(Seq("which", tool)).!(ProcessLogger(_ => ())) == 0
    catch case _: Exception => false

  private def requireTool(tool: String): Unit =
    if toolsRequired then assert(onPath(tool), s"$tool is required but is not on PATH")
    else assume(onPath(tool), s"$tool is not on PATH; skipping cross-language parcel oracle")

  private def runJson(command: Seq[String]): Json =
    val out = new StringBuilder
    val err = new StringBuilder
    val code = Process(command).!(ProcessLogger(
      line => { val _ = out.append(line) },
      line => { val _ = err.append(line).append('\n') }
    ))
    assertEquals(code, 0, s"${command.mkString(" ")} failed:\n$err")
    parse(out.result()).fold(e => fail(s"oracle did not print JSON: ${e.message}\n$out"), identity)

  private def string(json: Json, name: String): String =
    json.hcursor.get[String](name).fold(e => fail(e.message), identity)
  private def int(json: Json, name: String): Int =
    json.hcursor.get[Int](name).fold(e => fail(e.message), identity)

  test("R, Julia, and Scala agree on exact parcel identity and assignment bytes") {
    requireTool("Rscript")
    requireTool("julia")
    val r = runJson(Seq(
      "Rscript",
      scripts.resolve("parcel-oracle.R").toAbsolutePath.toString,
      rPackage.toAbsolutePath.toString
    ))
    val julia = runJson(Seq(
      "julia",
      scripts.resolve("parcel_oracle.jl").toAbsolutePath.toString
    ))

    val expectedFinite = Files.readAllBytes(fixture.resolve(
      "assets/sha256/72/7297cb3eb45df97653c90bfb586eee3857912df6fe0bdc789dd6c7ab849c9394"
    ))
    val expectedAssignment = Files.readAllBytes(fixture.resolve(
      "assets/sha256/da/daa961c1c1d1aa3cbc2455ed421c8868e2ef5dbab325f2bc403f6f2db66c398c"
    ))
    val finite64 = java.util.Base64.getEncoder.encodeToString(expectedFinite)
    val assignment64 = java.util.Base64.getEncoder.encodeToString(expectedAssignment)

    List(r, julia).foreach { oracle =>
      assertEquals(
        string(oracle, "finiteFingerprint"),
        "sha256:7297cb3eb45df97653c90bfb586eee3857912df6fe0bdc789dd6c7ab849c9394"
      )
      assertEquals(string(oracle, "finiteBytes"), finite64)
      assertEquals(int(oracle, "finiteSize"), expectedFinite.length)
      assertEquals(
        string(oracle, "assignmentDigest"),
        "sha256:daa961c1c1d1aa3cbc2455ed421c8868e2ef5dbab325f2bc403f6f2db66c398c"
      )
      assertEquals(string(oracle, "assignmentBytes"), assignment64)
      assertEquals(int(oracle, "assignmentSize"), expectedAssignment.length)
      assertNotEquals(string(oracle, "reorderedFingerprint"), string(oracle, "finiteFingerprint"))
      assertNotEquals(string(oracle, "foreignFingerprint"), string(oracle, "finiteFingerprint"))
    }
    assertEquals(r, julia)
  }
