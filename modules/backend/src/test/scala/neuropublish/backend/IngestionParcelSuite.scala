package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import munit.CatsEffectSuite
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.{Manifest, Problem}

/** Asset-dependent parcel admission: the server opens hard-assignment bytes before it derives any
  * rendition, for both inline and worker ingestion through `Derivation.deriveEach`.
  */
class IngestionParcelSuite extends CatsEffectSuite:
  private val fixture =
    List("modules/conformance/fixtures/parcel", "../conformance/fixtures/parcel")
      .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath))
      .getOrElse(fail("parcel fixture not found"))
  private lazy val originalJson = io.circe.parser.parse(
    java.nio.file.Files.readString((fixture / "manifest.json").toNioPath)
  ).fold(e => fail(e.message), identity)
  private lazy val original = Manifest.admit(originalJson).fold(
    ps => fail(Problem.render(ps)),
    identity
  )

  private def withAssignment(bytes: Array[Byte]): Manifest =
    val digest = Sha256.of(bytes)
    val updated = originalJson.hcursor.downField("assets").withFocus(_.mapArray(_.map { asset =>
      if asset.hcursor.get[String]("id").toOption.contains("parcel-assignment") then
        asset.mapObject(
          _.add("digest", Json.fromString(digest.render))
            .add("size", Json.fromLong(bytes.length.toLong))
        )
      else asset
    })).top.getOrElse(fail("could not rewrite assignment asset"))
    Manifest.admit(updated).fold(ps => fail(Problem.render(ps)), identity)

  private def i32le(values: Int*): Array[Byte] =
    values.iterator.flatMap { value =>
      Iterator(
        value.toByte,
        (value >>> 8).toByte,
        (value >>> 16).toByte,
        (value >>> 24).toByte
      )
    }.toArray

  private def deriveWith(bytes: Array[Byte]): IO[Either[String, List[Derivation.Derived]]] =
    Files[IO].tempDirectory.use { dir =>
      val manifest = withAssignment(bytes)
      val digest = manifest.asset("parcel-assignment").get.digest
      val store = ObjectStore.LocalFs(dir)
      store.put(digest, bytes).flatMap(r =>
        IO.fromEither(r.leftMap(new IllegalStateException(_))) *>
          Derivation.deriveAll(store, manifest)
      )
    }

  test("valid parcel mapping is proven before its pullback volume is derived") {
    val store = ObjectStore.LocalFs(fixture / "assets")
    Derivation.deriveAll(store, original).map { result =>
      val derived = result.fold(message => fail(message), identity)
      assertEquals(derived.map(d => (d.assetId, d.kind)), List(("parcel-pullback", "volume")))
    }
  }

  test("an in-range-sized assignment with an out-of-range ordinal fails ingestion") {
    deriveWith(i32le(0, 0, 1, 1, 2, 2, 3, 4)).map { result =>
      val message = result.left.getOrElse(fail("invalid assignment reached rendition derivation"))
      assert(message.contains("/domainMappings/0/descriptor/payload/asset"), message)
      assert(message.contains("source ordinal 7 has target ordinal 4"), message)
    }
  }

  test("a declared complete assignment with an empty target fails ingestion") {
    deriveWith(i32le(0, 0, 1, 1, 2, 2, 0, 1)).map { result =>
      val message = result.left.getOrElse(fail("false complete coverage was accepted"))
      assert(message.contains("/domainMappings/0/descriptor/payload/coverage"), message)
      assert(message.contains("schaefer2018-7networks-rh-default-1"), message)
    }
  }
