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

  /** The reference bundle's file for an asset id (NIfTI volumes, GIFTI surfaces and fields). */
  private def fileOf(id: String): String =
    if id.endsWith("-pial") then s"$id.surf.gii"
    else if id.endsWith("-lh") || id.endsWith("-rh") then s"$id.func.gii"
    else s"$id.nii"

  /** The bytes of the two digest-only assets in the staging bundle (`speech-effect`, `speech-se`),
    * staged at their normalized location with matching `digest`/`size`.
    */
  private val digestOnly: Map[String, Array[Byte]] = Map(
    "speech-effect" -> "effect bytes".getBytes("UTF-8"),
    "speech-se" -> "standard error bytes".getBytes("UTF-8")
  )

  /** A staging bundle: the reference manifest with `path` instead of `digest`/`size` for the three
    * real assets, two digest-only assets whose bytes sit under `assets/sha256/`, plus an unknown
    * field that must survive.
    */
  private def staging(dir: Path, extra: String => String = identity): IO[Unit] =
    for
      ref <- bytes("reference/manifest.json").map(b => new String(b, "UTF-8"))
      json = parse(ref).toOption.get
      assets = json.hcursor.downField("assets").as[List[io.circe.Json]].toOption.get
      staged = assets.map { a =>
        val id = a.hcursor.get[String]("id").toOption.get
        a.mapObject(o =>
          digestOnly.get(id) match
            case Some(b) =>
              o.add("digest", io.circe.Json.fromString(Sha256.of(b).render))
                .add("size", io.circe.Json.fromInt(b.length))
            case None =>
              o.remove("digest").remove("size")
                .add("path", io.circe.Json.fromString(s"in/${fileOf(id)}"))
        )
      }
      manifest = json.mapObject(
        _.add("assets", io.circe.Json.arr(staged*)).add("lab", io.circe.Json.fromString("rotman"))
      )
      _ <- Files[IO].createDirectories(dir / "in")
      _ <- assets.map(_.hcursor.get[String]("id").toOption.get).filterNot(digestOnly.contains)
        .traverse_(id =>
          Files[IO].copy(
            fixtures / "reference" / "assets" / fileOf(id),
            dir / "in" / fileOf(id)
          )
        )
      _ <- digestOnly.values.toList.traverse_ { b =>
        val d = Sha256.of(b)
        val sub = dir / "assets" / "sha256" / d.hex.take(2)
        Files[IO].createDirectories(sub) *>
          fs2.Stream.emits(b).through(Files[IO].writeAll(sub / d.hex)).compile.drain
      }
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
        assertEquals(
          packed.assets.map(_._1),
          List(
            "t1",
            "speech-effect",
            "speech-se",
            "speech-t",
            "speech-z",
            "lh-pial",
            "rh-pial",
            "speech-t-lh",
            "speech-t-rh",
            "speech-z-lh",
            "speech-z-rh"
          )
        )
        // digest-only assets are copied from the staging layout into the output
        digestOnly.foreach { (id, b) =>
          val d = Sha256.of(b)
          val copied = out / "assets" / "sha256" / d.hex.take(2) / d.hex
          assert(java.nio.file.Files.exists(copied.toNioPath), id)
          assertEquals(m.asset(id).map(_.size), Some(b.length.toLong))
        }
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

  tmp.test("pack never overwrites a declared digest or size that disagrees with the file") { dir =>
    val wrong = "sha256:" + "1" * 64
    for
      _ <- staging(
        dir / "staging",
        _.replace(
          "\"path\" : \"in/t1.nii\"",
          s"\"path\" : \"in/t1.nii\", \"digest\" : \"$wrong\", \"size\" : 7"
        )
      )
      r <- Pack.pack(dir / "staging", dir / "out.npub")
      se = Sha256.of(digestOnly("speech-se")).render
      _ <- staging(dir / "staging2", _.replace(s"\"digest\" : \"$se\"", s"\"digest\" : \"$wrong\""))
      r2 <- Pack.pack(dir / "staging2", dir / "out2.npub")
    yield
      assertEquals(r.left.map(_.map(_.pointer)), Left(List("/assets/0/digest", "/assets/0/size")))
      // a digest-only asset whose bytes are not staged under that digest
      assertEquals(r2.left.map(_.map(_.pointer)), Left(List("/assets/2")))
      assert(!java.nio.file.Files.exists((dir / "out.npub").toNioPath))
  }

  tmp.test("pack refuses a directory path, a non-array assets, and an existing output") { dir =>
    for
      _ <- staging(dir / "staging", _.replace("\"path\" : \"in/t1.nii\"", "\"path\" : \".\""))
      r <- Pack.pack(dir / "staging", dir / "out.npub")
      _ <- staging(
        dir / "staging2",
        s => {
          val i = s.indexOf("\"assets\" : [")
          val j = s.indexOf("\n  ]", i)
          s.substring(0, i) + "\"assets\" : {}" + s.substring(j + 4)
        }
      )
      r2 <- Pack.pack(dir / "staging2", dir / "out2.npub")
      _ <- staging(dir / "staging3")
      ok <- Pack.pack(dir / "staging3", dir / "out3.npub")
      again <- Pack.pack(dir / "staging3", dir / "out3.npub")
      forced <- Pack.pack(dir / "staging3", dir / "out3.npub", force = true)
    yield
      assertEquals(r.left.map(_.map(_.pointer)), Left(List("/assets/0/path")))
      assert(r.left.exists(_.head.message.contains("not a regular file")), r)
      assertEquals(r2.left.map(_.map(_.pointer)), Left(List("/assets")))
      assert(ok.isRight, ok)
      assert(again.left.exists(_.head.message.contains("--force")), again)
      assert(forced.isRight, forced)
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
            "speech-t  t statistic (t)  volume:speech-t, surface:speech-t-lh, surface:speech-t-rh  domain mni-2mm  display recommended"
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
