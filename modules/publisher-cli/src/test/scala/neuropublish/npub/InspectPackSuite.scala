package neuropublish.npub

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.parser.parse
import munit.CatsEffectSuite
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest

class InspectPackSuite extends CatsEffectSuite:
  private val tmp = ResourceFunFixture(Files[IO].tempDirectory)
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath))
    .getOrElse(fail("fixtures not found"))

  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)

  /** A staging bundle: the reference manifest with `path` instead of `digest`/`size` for the two
    * real assets and a dropped placeholder pair, plus an unknown field that must survive.
    */
  private def staging(dir: Path, extra: String => String = identity): IO[Unit] =
    for
      ref <- bytes("reference/manifest.json").map(b => new String(b, "UTF-8"))
      json = parse(ref).toOption.get
      assets = json.hcursor.downField("assets").as[List[io.circe.Json]].toOption.get
      staged = assets.map { a =>
        val id = a.hcursor.get[String]("id").toOption.get
        a.mapObject(o =>
          if id == "speech-effect" || id == "speech-se" then o
          else
            o.remove("digest").remove("size").add("path", io.circe.Json.fromString(s"in/$id.nii"))
        )
      }
      manifest = json.mapObject(
        _.add("assets", io.circe.Json.arr(staged*)).add("lab", io.circe.Json.fromString("rotman"))
      )
      _ <- Files[IO].createDirectories(dir / "in")
      _ <- List("t1", "speech-t", "speech-z").traverse_(id =>
        Files[IO].copy(fixtures / "reference" / "assets" / s"$id.nii", dir / "in" / s"$id.nii")
      )
      _ <- fs2.Stream.emits(extra(manifest.spaces2).getBytes("UTF-8"))
        .through(Files[IO].writeAll(dir / "manifest.json")).compile.drain
    yield ()

  tmp.test("pack hashes local files into the normalized layout and prints the manifest digest") {
    dir =>
      val out = dir / "out.npub"
      for
        _ <- staging(dir / "staging")
        packed <-
          Pack.pack(dir / "staging", out).map(_.fold(ps => fail(ps.mkString("; ")), identity))
        written <- Files[IO].readAll(out / "manifest.json").compile.to(Array)
        t1 <- bytes("reference/assets/t1.nii")
      yield
        val (digest, m) = Manifest.parse(written).fold(ps => fail(ps.mkString("; ")), identity)
        assertEquals(digest.hex, packed.manifestDigest.hex)
        assertEquals(packed.assets.map(_._1), List("t1", "speech-t", "speech-z"))
        val t1Digest = Sha256.of(t1)
        assertEquals(m.asset("t1").map(_.digest.hex), Some(t1Digest.hex))
        assertEquals(m.asset("t1").map(_.size), Some(t1.length.toLong))
        assertEquals(
          m.asset("t1").flatMap(_.catalog),
          Some("templateflow:MNI152NLin2009cAsym/T1w/res-02")
        )
        assert(!new String(written, "UTF-8").contains("\"path\""))
        assertEquals(m.raw.hcursor.get[String]("lab"), Right("rotman"))
        val stored = out / "assets" / "sha256" / t1Digest.hex.take(2) / t1Digest.hex
        assert(java.nio.file.Files.exists(stored.toNioPath), stored.toString)
        assert(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(stored.toNioPath), t1))
  }

  tmp.test("pack refuses a path that escapes the staging directory") { dir =>
    for
      _ <- staging(
        dir / "staging",
        _.replace("\"path\" : \"in/t1.nii\"", "\"path\" : \"../escape.nii\"")
      )
      _ <-
        fs2.Stream.emits("x".getBytes).through(Files[IO].writeAll(dir / "escape.nii")).compile.drain
      r <- Pack.pack(dir / "staging", dir / "out.npub")
    yield
      assertEquals(r.left.map(_.map(_.pointer)), Left(List("/assets/0/path")))
      assert(r.left.exists(_.head.message.contains("escapes")))
      assert(!java.nio.file.Files.exists((dir / "out.npub").toNioPath))
  }

  tmp.test("pack refuses an unresolved catalog reference and a missing file") { dir =>
    for
      _ <- staging(dir / "staging", _.replace("\"path\" : \"in/t1.nii\"", "\"unresolved\" : true"))
      r <- Pack.pack(dir / "staging", dir / "out.npub")
      _ <- staging(
        dir / "staging2",
        _.replace("\"path\" : \"in/speech-z.nii\"", "\"path\" : \"in/missing.nii\"")
      )
      r2 <- Pack.pack(dir / "staging2", dir / "out2.npub")
    yield
      assertEquals(r.left.map(_.map(_.pointer)), Left(List("/assets/0/catalog")))
      assertEquals(r2.left.map(_.map(_.pointer)), Left(List("/assets/4/path")))
  }

  tmp.test(
    "inspect prints the tree, assets, warnings, records, and no problems for the reference"
  ) {
    _ =>
      bytes("reference/manifest.json").map { b =>
        val (lines, ok) = Inspect.render(b)
        assert(ok)
        val text = lines.mkString("\n")
        assert(lines.exists(_.startsWith("title      Sherlock")), text)
        assert(lines.contains("core       0.1"), text)
        assert(lines.exists(_.startsWith("digest     sha256:")), text)
        assert(
          lines.exists(_.contains(
            "group-model  Group model · speech features  n=26  method org.bbuchsbaum.fmrigds/reducer/meta-random-effects@1.2"
          )),
          text
        )
        assert(
          lines.exists(_.contains(
            "speech-t  t statistic (t)  volume:speech-t  domain mni-2mm  display recommended"
          )),
          text
        )
        assert(
          lines.exists(_.contains(
            "t1  54112 B  application/x-nifti  sha256:7df88c4c806f…  catalog templateflow"
          )),
          text
        )
        assert(
          lines.exists(_.contains("heterogeneous-temporal-noise  (analysis group-model)")),
          text
        )
        assert(
          lines.exists(_.contains(
            "/domains/0/descriptor  org.neuropublish.domain/volume-grid@1.0  org.neuropublish.domain/volume-grid@1.0: trusted"
          )),
          text
        )
        assert(
          lines.exists(
            _.contains("/provenance/activities/2  org.example.lab/smooth@0.3  unsupported")
          ),
          text
        )
        assertEquals(lines.last, "problems  none")
      }
  }

  tmp.test("inspect still prints the projection and then every problem for a bad bundle") { _ =>
    bytes("invalid/duplicate-estimand-order.json").map { b =>
      val (lines, ok) = Inspect.render(b)
      assert(!ok)
      assert(lines.exists(_.startsWith("title      Sherlock")))
      assert(lines.contains("problems  1"), lines.mkString("\n"))
      assertEquals(
        lines.last,
        "error  /analyses/0/estimands/2/order: estimand order 1 is already used in analysis 'group-model'"
      )
    }
  }
