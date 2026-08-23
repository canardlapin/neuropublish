package neuropublish.backend

import cats.effect.IO
import fs2.io.file.{Files, Path}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import neuropublish.api.Provenance
import neuropublish.api.Stage4.given

/** `understood` is an allow-list of schema ids the server reads, never a namespace; the cache is
  * stamped with the rule version so a rule change recomputes every revision.
  */
class ProvenanceModelSuite extends CatsEffectSuite:
  private def activity(id: String, schema: String): Json =
    Json.obj(
      "id" -> id.asJson,
      "schema" -> Json.obj("id" -> schema.asJson, "version" -> "1.0".asJson),
      "payload" -> Json.obj("k" -> 1.asJson)
    )
  private def manifest(activities: Json*): Json =
    Json.obj(
      "assets" -> Json.arr(),
      "provenance" -> Json.obj("activities" -> Json.arr(activities*), "edges" -> Json.arr())
    )

  test("only the readers the server has are understood; a same-namespace stranger is not") {
    assertEquals(ProvenanceModel.interpretation(ProvenanceModel.ReceiptSchema), "understood")
    assertEquals(ProvenanceModel.interpretation(ProvenanceModel.GroupReducerSchema), "understood")
    assertEquals(ProvenanceModel.interpretation("org.bbuchsbaum.evil/anything"), "unsupported")
    assertEquals(
      ProvenanceModel.interpretation("org.neuropublish.view/workspace-state"),
      "unsupported"
    )
    val p = ProvenanceModel.compute(
      "r1",
      manifest(
        activity("good", ProvenanceModel.ReceiptSchema),
        activity("evil", "org.bbuchsbaum.evil/anything")
      )
    )
    assertEquals(
      p.nodes.map(n => n.id -> n.interpretation).toMap,
      Map(
        "good" -> "understood",
        "evil" -> "unsupported"
      )
    )
  }

  test("a cache from another computeVersion (or the old unstamped format) is recomputed") {
    Files[IO].tempDirectory.use { dir =>
      val p = dir / "provenance" / "r1.json"
      val m = manifest(activity("evil", "org.bbuchsbaum.evil/anything"))
      val stale = ProvenanceModel.compute("r1", m).copy(nodes =
        ProvenanceModel.compute("r1", m).nodes.map(_.copy(interpretation = "understood"))
      )
      for
        _ <- JsonFiles.write(p, stale) // old format: the bare Provenance, no version stamp
        fresh <- ProvenanceModel.cached(dir, "r1", IO.pure(m))
        _ = assertEquals(fresh.nodes.map(_.interpretation), List("unsupported"))
        raw <- Files[IO].readUtf8(p).compile.string
        _ = assert(raw.contains(s"\"computeVersion\" : ${ProvenanceModel.computeVersion}"), raw)
        // a stamped cache with a different version is also refreshed
        _ <- JsonFiles.write(
          p,
          Json.obj("computeVersion" -> 0.asJson, "provenance" -> stale.asJson)
        )
        again <- ProvenanceModel.cached(dir, "r1", IO.pure(m))
        // a current cache is served as is, without consulting the manifest
        served <- ProvenanceModel.cached(dir, "r1", IO.raiseError(new Exception("not needed")))
      yield
        assertEquals(again.nodes.map(_.interpretation), List("unsupported"))
        assertEquals(served, again)
    }
  }
