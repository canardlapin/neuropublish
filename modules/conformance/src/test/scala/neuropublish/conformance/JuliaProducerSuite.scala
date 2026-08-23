package neuropublish.conformance

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.backend.{ProjectKey, Server}
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{ByteProfile, Manifest}
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Authorization
import scala.sys.process.*

/** Stage 2 neutrality proof (ADR 0001 "Verification"): a Julia program that uses no Neuropublish
  * code writes a bundle the Scala side admits with the same digest, publishes it over the
  * documented HTTP API against an in-process server, and its manifest survives an R round trip with
  * unknown fields intact.
  *
  * Needs `julia` (with JSON3) and `Rscript` (with jsonlite and neuroim2) on PATH. Each test skips
  * with a message when a tool is absent, unless `NP_TEST_REQUIRE_TOOLS=1` or `CI=true` is set, in
  * which case a missing tool fails the suite (CI installs both and must run the proof, not skip
  * it). `fixtures/julia` is the committed output of the same script; one test regenerates it and
  * compares byte for byte, so the producer cannot drift silently on another Julia.
  */
class JuliaProducerSuite extends CatsEffectSuite:
  /** The publish test runs the producer three times (push, stale re-push, child push), each writing
    * four volumes, two surfaces, and four vertex fields: Julia start-up alone is most of the 30 s
    * default.
    */
  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(3, "min")
  private val scripts = List("julia", "modules/conformance/julia").map(Path(_))
    .find(p => java.nio.file.Files.isDirectory(p.toNioPath))
    .getOrElse(fail("julia directory not found from " + Path("").absolute))
  private val producer = (scripts / "producer.jl").absolute.toString
  private val roundtrip = (scripts / "roundtrip.R").absolute.toString
  private val niftiCheck = (scripts / "nifti-check.R").absolute.toString
  private val committed = List("fixtures/julia", "modules/conformance/fixtures/julia").map(Path(_))
    .find(p => java.nio.file.Files.isDirectory(p.toNioPath))
    .getOrElse(fail("fixtures/julia not found"))

  /** Tools are required (not merely assumed) when the environment says so. */
  private val toolsRequired: Boolean =
    sys.env.get("NP_TEST_REQUIRE_TOOLS").exists(v => v == "1" || v.equalsIgnoreCase("true")) ||
      sys.env.get("CI").exists(_.equalsIgnoreCase("true"))

  private def onPath(tool: String): Boolean =
    try Process(Seq("which", tool)).!(ProcessLogger(_ => ())) == 0
    catch case _: Exception => false

  private def requireTool(tool: String): Unit =
    if toolsRequired then
      assert(onPath(tool), s"$tool is not on PATH and NP_TEST_REQUIRE_TOOLS/CI is set")
    else assume(onPath(tool), s"$tool is not on PATH; skipping the Julia producer proof")

  /** Runs a command, capturing stdout lines; fails with stderr on a non-zero exit. */
  private def run(cmd: Seq[String]): IO[List[String]] = IO.blocking {
    val out = List.newBuilder[String]
    val err = new StringBuilder
    val code =
      Process(cmd).!(ProcessLogger(l => out += l, l => { val _ = err.append(l).append('\n') }))
    assert(code == 0, s"${cmd.mkString(" ")} exited $code\n$err")
    out.result()
  }

  private def printed(lines: List[String], key: String): String =
    lines.collectFirst { case l if l.startsWith(key + " ") => l.drop(key.length + 1).trim }
      .getOrElse(fail(s"producer did not print '$key'; output:\n${lines.mkString("\n")}"))

  private val key = ProjectKey("rotman", "sherlock")
  private val token = "julia-test-token"

  /** An in-process backend on a free loopback port, with the deprecated static token enabled. */
  private def server: Resource[IO, (String, HttpApp[IO])] =
    for
      data <- Files[IO].tempDirectory
      port <- Resource.eval(IO.blocking {
        val s = new java.net.ServerSocket(0)
        try s.getLocalPort
        finally s.close()
      })
      base = s"http://127.0.0.1:$port"
      app <- Resource.eval(Server.build(data, token, key, base).map(_.orNotFound))
      _ <- EmberServerBuilder.default[IO].withHost(host"127.0.0.1")
        .withPort(Port.fromInt(port).get).withHttpApp(app).build
    yield (base, app)

  private val bundle = ResourceFunFixture(Files[IO].tempDirectory)

  bundle.test("the Julia bundle is admitted with Julia's digest and its unknown fields intact") {
    dir =>
      requireTool("julia")
      for
        lines <- run(Seq("julia", producer, "--out", dir.toString))
        julia = printed(lines, "digest")
        bytes <- Files[IO].readAll(dir / "manifest.json").compile.to(Array)
      yield
        val admitted = ByteProfile.admit(bytes)
          .fold(vs => fail(vs.map(_.render).mkString("; ")), identity)
        assertEquals(admitted.render, julia)
        val (digest, manifest) =
          Manifest.parse(bytes).fold(m => fail(s"manifest rejected: $m"), identity)
        assertEquals(digest.render, julia)
        assertEquals(manifest.core, "0.1")
        assertEquals(manifest.underlays.map(_.asset), List("t1"))
        assertEquals(
          manifest.resultFields.map(_.measure),
          List("effect", "t-statistic", "z-statistic").map("org.neuropublish.measure/" + _)
        )
        assertUnknownFieldsPresent(manifest.raw)
        // asset digests and sizes are over the files the producer wrote
        assertEquals(manifest.surfaces.map(_.id), List("lh-pial", "rh-pial"))
        manifest.assets.foreach { a =>
          val file =
            java.nio.file.Files.readAllBytes((dir / "assets" / fileOf(manifest, a)).toNioPath)
          assertEquals(Sha256.of(file).render, a.digest.render, a.id)
          assertEquals(file.length.toLong, a.size, a.id)
        }
  }

  bundle.test("producer.jl --out reproduces the committed fixtures/julia byte for byte") { dir =>
    requireTool("julia")
    def bytes(p: Path) = Files[IO].readAll(p).compile.to(Array)
    for
      _ <- run(Seq("julia", producer, "--out", dir.toString))
      written <- Files[IO].walk(dir).evalFilter(p => Files[IO].isRegularFile(p)).compile.toList
      rel = written.map(p => dir.toNioPath.relativize(p.toNioPath).toString).sorted
      expected <- Files[IO].walk(committed).evalFilter(p => Files[IO].isRegularFile(p))
        .compile.toList
        .map(_.map(p => committed.toNioPath.relativize(p.toNioPath).toString).sorted)
      _ = assertEquals(rel, expected)
      _ <- rel.traverse_ { r =>
        (bytes(dir / r), bytes(committed / r)).mapN { (a, b) =>
          assert(
            java.util.Arrays.equals(a, b),
            s"$r differs from the committed fixture; regenerate fixtures/julia with producer.jl --out and review the diff"
          )
        }
      }
    yield ()
  }

  bundle.test("neuroim2 reads back the Julia volumes with the shape, affine, and values written") {
    dir =>
      requireTool("julia")
      requireTool("Rscript")
      for
        _ <- run(Seq("julia", producer, "--out", dir.toString))
        lines <- run(Seq("Rscript", niftiCheck, dir.toString))
        _ = assert(lines.exists(_.startsWith("checked 4 volumes")), lines.mkString("\n"))
        // the check is not vacuous: a corrupted volume fails it
        t1 = dir / "assets" / "t1.nii"
        bytes <- Files[IO].readAll(t1).compile.to(Array)
        _ = { bytes(352) = (bytes(352) ^ 0x40).toByte }
        _ <- fs2.Stream.emits(bytes).through(Files[IO].writeAll(t1)).compile.drain
        bad <- IO.blocking(Process(Seq("Rscript", niftiCheck, dir.toString)).!(ProcessLogger(_ =>
          ()
        )))
      yield assertNotEquals(bad, 0, "a corrupted volume passed nifti-check.R")
  }

  bundle.test("the Julia producer publishes over the HTTP API and every rendition is ready") {
    dir =>
      requireTool("julia")
      server.use { (base, app) =>
        for
          lines <- run(Seq(
            "julia",
            producer,
            "--out",
            dir.toString,
            "--server",
            base,
            "--project",
            key.render,
            "--token",
            token,
            "--message",
            "julia neutrality proof"
          ))
          julia = printed(lines, "digest")
          revision = printed(lines, "revision")
          _ = assertEquals(printed(lines, "server-digest"), julia)
          _ = assertEquals(printed(lines, "viewUrl"), s"$base/w/rotman/p/sherlock/r/$revision/view")
          bytes <- Files[IO].readAll(dir / "manifest.json").compile.to(Array)
          _ = assertEquals(Sha256.of(bytes).render, julia)
          // independent check through the server: stored digest, renditions, unknown fields
          detail <- app.run(Request[IO](
            Method.GET,
            Uri.unsafeFromString(s"/api/v1/revisions/$revision")
          ).putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))))
          _ = assertEquals(detail.status, Status.Ok)
          d <- detail.as[Json].map(_.as[RevisionDetail].fold(e => fail(e.getMessage), identity))
          _ = assertEquals(d.digest, julia)
          _ = assertEquals(d.parent, None)
          _ = assertEquals(d.message, Some("julia neutrality proof"))
          _ = assertEquals(
            d.renditions.map(_.assetId).sorted,
            List(
              "lh-pial",
              "rh-pial",
              "speech-effect",
              "speech-t",
              "speech-t-lh",
              "speech-t-rh",
              "speech-z",
              "speech-z-lh",
              "speech-z-rh",
              "t1"
            )
          )
          _ = assertEquals(
            d.renditions.map(r => (r.kind, r.surface)).distinct.sorted,
            List(
              ("surface-mesh", None),
              ("vertex-field", Some("lh-pial")),
              ("vertex-field", Some("rh-pial")),
              ("volume", None)
            )
          )
          _ = assert(d.renditions.forall(_.status == "ready"), d.renditions.toString)
          _ = assertUnknownFieldsPresent(d.manifest)
          // a second push without --parent is stale: the producer must fail, not silently re-push
          again <- IO.blocking(Process(Seq(
            "julia",
            producer,
            "--out",
            dir.toString,
            "--server",
            base,
            "--project",
            key.render,
            "--token",
            token
          )).!(ProcessLogger(_ => ())))
          _ = assertNotEquals(again, 0, "stale parent was accepted")
          // with --parent the same bundle publishes again as a child revision
          child <- run(Seq(
            "julia",
            producer,
            "--out",
            dir.toString,
            "--server",
            base,
            "--project",
            key.render,
            "--token",
            token,
            "--parent",
            revision
          ))
          _ = assertEquals(printed(child, "server-digest"), julia)
          _ = assertNotEquals(printed(child, "revision"), revision)
        yield ()
      }
  }

  bundle.test("an R decode/re-encode of the Julia manifest is value-equal with unknown fields") {
    dir =>
      requireTool("julia")
      requireTool("Rscript")
      for
        _ <- run(Seq("julia", producer, "--out", dir.toString))
        original <- Files[IO].readAll(dir / "manifest.json").compile.to(Array)
        _ <- run(Seq(
          "Rscript",
          roundtrip,
          (dir / "manifest.json").toString,
          (dir / "roundtrip.json").toString
        ))
        rewritten <- Files[IO].readAll(dir / "roundtrip.json").compile.to(Array)
      yield
        // re-encoding changes the bytes (and so the digest) but not the value (ADR 0001)
        assertNotEquals(Sha256.of(rewritten).hex, Sha256.of(original).hex)
        val before = parse(String(original, "UTF-8")).fold(e => fail(e.message), identity)
        ByteProfile.admit(rewritten).left.foreach(vs => fail(vs.map(_.render).mkString("; ")))
        val after = parse(String(rewritten, "UTF-8")).fold(e => fail(e.message), identity)
        assertEquals(after, before)
        assertUnknownFieldsPresent(after)
        // and Scala still admits the R spelling as a manifest
        Manifest.parse(rewritten).fold(m => fail(s"R output rejected: $m"), _ => ())
  }

  /** The producer's file for an asset: NIfTI volumes, GIFTI surfaces and vertex fields. */
  private def fileOf(m: Manifest, a: neuropublish.protocol.json.ManifestAsset): String =
    if a.mediaType == "application/x-nifti" then s"${a.id}.nii"
    else if m.surfaceAssetIds.contains(a.id) then s"${a.id}.surf.gii"
    else s"${a.id}.func.gii"

  /** The unknown top-level field and the unknown field inside a known record (assets[0]). */
  private def assertUnknownFieldsPresent(raw: Json): Unit =
    val top = raw.hcursor.downField("x-julia-producer")
    assertEquals(top.downField("version").as[String], Right("0.1"))
    assertEquals(
      top.downField("nested").downField("flags").as[List[Boolean]],
      Right(List(true, false))
    )
    assertEquals(top.downField("nested").downField("empty").as[List[Json]], Right(Nil))
    assertEquals(top.downField("nested").downField("ratio").as[Double], Right(0.125))
    val asset = raw.hcursor.downField("assets").downArray.downField("x-julia-voxelStats")
    assertEquals(asset.downField("max").as[Double], Right(100.0))
    val denoise = raw.hcursor.downField("provenance").downField("activities").values
      .getOrElse(fail("no activities")).find(_.hcursor.downField("id").as[String] ==
        Right("denoise")).getOrElse(fail("no denoise activity"))
    assertEquals(
      denoise.hcursor.downField("schema").downField("id").as[String],
      Right("org.example.julia/denoise")
    )
