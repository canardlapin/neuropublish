package neuropublish.ingestion

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.syntax.*
import java.time.Instant
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.backend.*
import neuropublish.protocol.Sha256
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*

/** Worker mode end to end over the local-filesystem queue: commit enqueues and the revision reports
  * `pending`; one worker pass makes it `ready`; an asset that cannot render fails after three
  * attempts with the error on the job.
  */
class WorkerSuite extends CatsEffectSuite:
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val token = "t"

  final case class Env(app: HttpApp[IO], stores: Server.Storage, data: Path, worker: Worker)
  private val env = ResourceFunFixture(
    Files[IO].tempDirectory.evalMap { dir =>
      val stores = Server.localStorage(dir, IngestionMode.Worker)
      for
        routes <-
          Server.build(dir, key, "http://test", legacyToken = Some(token), storage = Some(stores))
        revisions <- LocalRevisionStore(dir)
      yield Env(
        routes.orNotFound,
        stores,
        dir,
        Worker(stores.objects, stores.renditions, stores.queue, revisions, _ => IO.unit)
      )
    }
  )
  private def auth(r: Request[IO]) =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)
  private def decode[A: io.circe.Decoder](r: org.http4s.Response[IO]): IO[A] =
    r.as[String].flatMap(b => IO.fromEither(io.circe.parser.decode[A](b)))
  private val assetIds = List("t1", "speech-effect", "speech-se", "speech-t", "speech-z")

  /** Pushes the reference bundle; `substitute` swaps one asset's bytes (its digest in the inventory
    * and manifest stay the asset's own, so the manifest must be rewritten to match).
    */
  private def push(app: HttpApp[IO], junk: Option[String] = None): IO[CommitResult] =
    for
      manifest0 <- bytes("reference/manifest.json")
      assets0 <- assetIds.traverse(id => bytes(s"reference/assets/$id.nii").map(id -> _))
      junkBytes = Array.fill[Byte](54112)(1) // same size, not a NIfTI volume
      assets = assets0.map((id, b) => if junk.contains(id) then id -> junkBytes else id -> b)
      manifest = junk.fold(manifest0) { id =>
        val was = Sha256.of(assets0.find(_._1 == id).get._2).render
        new String(manifest0, "UTF-8").replace(was, Sha256.of(junkBytes).render).getBytes("UTF-8")
      }
      inv = assets.map((_, b) =>
        AssetInventory(Sha256.of(b).render, b.length.toLong, "application/x-nifti")
      )
      s <- app.run(auth(Request[IO](
        Method.POST,
        uri"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions"
      ).withEntity(CreateUploadSession(
        Sha256.of(manifest).render,
        manifest.length.toLong,
        None,
        inv
      ).asJson)))
        .flatMap(decode[UploadSessionCreated])
      _ <- assets.traverse_((_, b) =>
        app.run(auth(Request[IO](
          Method.PUT,
          Uri.unsafeFromString(
            s"/api/v1/upload-sessions/${s.sessionId}/objects/${Sha256.of(b).render}"
          )
        ).withEntity(b))).map(r => assertEquals(r.status, Status.NoContent))
      )
      _ <- app.run(auth(Request[IO](
        Method.PUT,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/manifest")
      ).withEntity(manifest)))
      c <- app.run(auth(Request[IO](
        Method.POST,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/commit")
      ).withEntity(CommitRequest(None).asJson)))
      _ = assertEquals(c.status, Status.Created)
      r <- decode[CommitResult](c)
    yield r

  private def detail(app: HttpApp[IO], rev: String) =
    app.run(auth(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/revisions/$rev"))))
      .flatMap(decode[RevisionDetail])

  env.test("commit enqueues; the revision is pending until one worker pass makes it ready") { e =>
    for
      r <- push(e.app)
      d0 <- detail(e.app, r.revisionId)
      _ = assertEquals(d0.ingestion.map(_.status), Some("pending"))
      _ = assertEquals(d0.renditions.map(_.status).distinct, List("pending"))
      hdr <- e.app.run(auth(Request[IO](
        Method.GET,
        Uri.unsafeFromString(s"/api/v1/revisions/${r.revisionId}/renditions/speech-t/header")
      )))
      _ = assertEquals(hdr.status, Status.NotFound)
      now <- IO.realTimeInstant
      done <- e.worker.runOnce(now)
      _ = assertEquals(done.map(_.revisionId), Some(r.revisionId))
      idle <- e.worker.runOnce(now)
      _ = assertEquals(idle, None)
      d1 <- detail(e.app, r.revisionId)
      _ = assertEquals(d1.ingestion.map(_.status), Some("ready"))
      _ = assertEquals(d1.ingestion.map(_.attempts), Some(0))
      _ = assertEquals(d1.renditions.map(_.status).distinct, List("ready"))
      hdr2 <- e.app.run(auth(Request[IO](
        Method.GET,
        Uri.unsafeFromString(s"/api/v1/revisions/${r.revisionId}/renditions/speech-t/header")
      ))).flatMap(_.as[String])
    yield assert(hdr2.contains("volume-f32"))
  }

  env.test("an unreadable asset fails after three attempts with back off and records the error") {
    e =>
      for
        r <- push(e.app, junk = Some("speech-t"))
        t0 <- IO.realTimeInstant
        a1 <- e.worker.runOnce(t0)
        _ = assertEquals(a1.map(_.revisionId), Some(r.revisionId))
        j1 <- e.stores.queue.status(r.revisionId)
        _ = assertEquals(j1.map(_.status), Some("pending"))
        _ = assertEquals(j1.map(_.attempts), Some(1))
        _ =
          assert(j1.flatMap(_.nextAttemptAt).exists(n => Instant.parse(n).isAfter(t0)), "back off")
        early <- e.worker.runOnce(t0) // back off has not elapsed
        _ = assertEquals(early, None)
        _ <- e.worker.runOnce(t0.plusSeconds(10))
        _ <- e.worker.runOnce(t0.plusSeconds(100))
        j3 <- e.stores.queue.status(r.revisionId)
        _ = assertEquals(j3.map(_.status), Some("failed"))
        _ = assertEquals(j3.map(_.attempts), Some(3))
        _ = assert(j3.flatMap(_.error).exists(_.contains("speech-t")), j3.flatMap(_.error))
        none <- e.worker.runOnce(t0.plusSeconds(1000))
        _ = assertEquals(none, None)
        d <- detail(e.app, r.revisionId)
        _ = assertEquals(d.ingestion.map(_.status), Some("failed"))
        _ = assertEquals(d.ingestion.flatMap(_.error), j3.flatMap(_.error))
        _ = assertEquals(d.renditions.find(_.assetId == "speech-t").map(_.status), Some("failed"))
        // the readable assets before the junk one were derived; the head still moved (worker mode)
        _ = assertEquals(d.renditions.find(_.assetId == "t1").map(_.status), Some("ready"))
        head <-
          e.app.run(auth(Request[IO](Method.GET, uri"/api/v1/workspaces/rotman/projects/sherlock")))
            .flatMap(decode[ProjectSummary])
      yield assertEquals(head.head, Some(r.revisionId))
  }

  test("the queue hands a job to exactly one claimant") {
    Files[IO].tempDirectory.use { dir =>
      val q = IngestionQueue.LocalFs(dir / "queue")
      for
        _ <- q.enqueue("rev-a")
        now <- IO.realTimeInstant
        both <- (q.claim(now), q.claim(now)).parTupled
        _ = assertEquals(List(both._1, both._2).flatten.map(_.revisionId), List("rev-a"))
        _ <- q.complete("rev-a")
        st <- q.status("rev-a")
      yield assertEquals(st.map(_.status), Some("ready"))
    }
  }
