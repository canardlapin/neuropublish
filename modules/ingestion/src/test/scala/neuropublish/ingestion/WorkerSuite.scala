package neuropublish.ingestion

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.syntax.*
import java.time.Instant
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.api.Stage4.given
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

  /** Pushes the reference bundle; `substitute` swaps one asset's bytes (its digest in the inventory
    * and manifest stay the asset's own, so the manifest must be rewritten to match).
    */
  private def push(
      app: HttpApp[IO],
      junk: Option[String] = None,
      substitute: Option[(String, Array[Byte])] = None
  ): IO[CommitResult] =
    for
      manifest0 <- bytes("reference/manifest.json")
      assets0 <- ReferenceBundle.assets.traverse((id, file, _) =>
        bytes(s"reference/assets/$file").map(id -> _)
      )
      junkBytes = Array.fill[Byte](54112)(1) // same size, not a NIfTI volume
      swap = junk.map(_ -> junkBytes).orElse(substitute) // same size: only the digest changes
      assets = assets0.map((id, b) => id -> swap.filter(_._1 == id).map(_._2).getOrElse(b))
      manifest = swap.fold(manifest0) { (id, sb) =>
        val was = Sha256.of(assets0.find(_._1 == id).get._2).render
        new String(manifest0, "UTF-8").replace(was, Sha256.of(sb).render).getBytes("UTF-8")
      }
      inv = assets.zip(ReferenceBundle.assets).map((e, a) =>
        AssetInventory(Sha256.of(e._2).render, e._2.length.toLong, a._3)
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
      _ = assertEquals(d1.ingestion.map(_.attempts), Some(1)) // attempts are counted on claim
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

  env.test("a surface whose triangles do not realize its domain's key fails the job (Stage 5)") {
    e =>
      for
        original <- bytes("reference/assets/lh-pial.surf.gii")
        r <- push(e.app, substitute = Some("lh-pial" -> permutedSurface(original)))
        t0 <- IO.realTimeInstant
        _ <- e.worker.runOnce(t0)
        _ <- e.worker.runOnce(t0.plusSeconds(10))
        _ <- e.worker.runOnce(t0.plusSeconds(100))
        job <- e.stores.queue.status(r.revisionId)
        d <- detail(e.app, r.revisionId)
      yield
        assertEquals(job.map(_.status), Some("failed"))
        assert(job.flatMap(_.error).exists(_.contains("surface lh-pial")), job.flatMap(_.error))
        assert(job.flatMap(_.error).exists(_.contains("triangles hash to")), job.flatMap(_.error))
        assertEquals(d.ingestion.map(_.status), Some("failed"))
        // the volumes before the surface were derived; the surface and its fields were not
        assertEquals(d.renditions.find(_.assetId == "t1").map(_.status), Some("ready"))
        assertEquals(d.renditions.find(_.assetId == "lh-pial").map(_.status), Some("failed"))
        assertEquals(d.renditions.find(_.assetId == "speech-t-lh").map(_.status), Some("failed"))
  }

  /** `lh-pial` with the first two vertex ordinals of face 0 swapped: same vertex set and byte
    * length, a different ordered topology, so its ADR 0005 key differs from the domain's.
    */
  private def permutedSurface(original: Array[Byte]): Array[Byte] =
    val text = new String(original, "UTF-8")
    val data = "<Data>([A-Za-z0-9+/=]+)</Data>".r.findAllMatchIn(text).toList
    assertEquals(data.length, 2)
    val tri = java.util.Base64.getDecoder.decode(data(1).group(1))
    for k <- 0 until 4 do
      val a = tri(k); tri(k) = tri(4 + k); tri(4 + k) = a
    val swapped = java.util.Base64.getEncoder.encodeToString(tri)
    (text.substring(0, data(1).start(1)) + swapped + text.substring(data(1).end(1)))
      .getBytes("UTF-8")

  env.test("a tampered stored manifest is refused by the worker and recorded on the job") { e =>
    for
      r <- push(e.app)
      md = Sha256.unsafe(r.digest.stripPrefix("sha256:"))
      _ <- JsonFiles.writeBytes(
        e.data / "objects" / "sha256" / md.hex.take(2) / md.hex,
        "{\"tampered\":true}".getBytes("UTF-8")
      )
      now <- IO.realTimeInstant
      _ <- e.worker.runOnce(now)
      job <- e.stores.queue.status(r.revisionId)
    yield
      assertEquals(job.map(_.status), Some("pending"))
      assert(job.flatMap(_.error).exists(_.contains("IntegrityError")), job.flatMap(_.error))
  }

  env.test("a revision with no job and missing renditions is failed, never ready by absence") {
    e =>
      for
        r <- push(e.app)
        _ <- Files[IO].delete(e.data / "queue" / s"${r.revisionId}.json")
        d <- detail(e.app, r.revisionId)
      yield
        assertEquals(d.ingestion.map(_.status), Some("failed"))
        assertEquals(d.ingestion.flatMap(_.error), Some("no ingestion job"))
  }

  env.test("a share link cannot be minted while the revision's ingestion is pending or failed") {
    e =>
      for
        r <- push(e.app, junk = Some("speech-t"))
        cookie <- e.app.run(Request[IO](Method.POST, uri"/api/v1/auth/login").withEntity(
          LoginRequest(Server.DefaultOwnerEmail, Server.DefaultOwnerPassword).asJson
        )).map(_.cookies.find(_.name == "np_session").get.content)
        v <- e.app.run(Request[IO](
          Method.POST,
          Uri.unsafeFromString(s"/api/v1/revisions/${r.revisionId}/views")
        ).addCookie("np_session", cookie).withEntity(
          SaveView("v", io.circe.Json.obj("layers" -> io.circe.Json.arr())).asJson
        )).flatMap(decode[SavedViewDetail])
        mint = e.app.run(Request[IO](
          Method.POST,
          Uri.unsafeFromString(s"/api/v1/views/${v.id}/versions/1/links")
        ).addCookie("np_session", cookie).withEntity(CreateShareLink(None).asJson))
        pending <- mint
        _ = assertEquals(pending.status, Status.BadRequest)
        _ <- decode[ApiError](pending).map(a => assert(a.message.contains("pending"), a.message))
        t0 <- IO.realTimeInstant
        _ <- e.worker.runOnce(t0)
        _ <- e.worker.runOnce(t0.plusSeconds(10))
        _ <- e.worker.runOnce(t0.plusSeconds(100))
        failed <- mint
        _ = assertEquals(failed.status, Status.BadRequest)
        msg <- decode[ApiError](failed).map(_.message)
      yield assert(msg.contains("failed"), msg)
  }

  test("the queue hands a job to exactly one of two racing claimants") {
    Files[IO].tempDirectory.use { dir =>
      val q = LocalIngestionQueue(dir / "queue")
      for
        _ <- q.enqueue("rotman", "rev-a")
        now <- IO.realTimeInstant
        // both claimants hold the same pending snapshot: the race is on the claim file alone
        job <- q.status("rev-a").map(_.get)
        (a, b) <- (q.tryClaim(job, now, "w-a"), q.tryClaim(job, now, "w-b")).parTupled
        winners = List(a, b).flatten
        _ = assertEquals(
          winners.map(j => (j.revisionId, j.status, j.attempts)),
          List(("rev-a", "running", 1))
        )
        claim <- q.claimOf("rev-a")
        _ = assertEquals(claim.map(_.claimant), Some(if a.isDefined then "w-a" else "w-b"))
        none <- q.claim(now, "w-c") // still running inside the lease
        _ = assertEquals(none, None)
        _ <- q.complete("rev-a")
        st <- q.status("rev-a")
        gone <- q.claimOf("rev-a")
      yield
        assertEquals(st.map(_.status), Some("ready"))
        assertEquals(gone, None)
    }
  }

  test("a running job whose claim is older than the lease is reclaimed, counting the attempt") {
    Files[IO].tempDirectory.use { dir =>
      val q = LocalIngestionQueue(dir / "queue")
      for
        _ <- q.enqueue("rotman", "rev-a")
        t0 <- IO.realTimeInstant
        first <- q.claim(t0, "dead-worker")
        _ = assertEquals(first.map(_.attempts), Some(1))
        held <- q.claim(t0.plusSeconds(60), "w-b")
        _ = assertEquals(held, None)
        reclaimed <- q.claim(t0.plus(IngestionQueue.Lease).plusSeconds(1), "w-b")
        _ = assertEquals(reclaimed.map(j => (j.status, j.attempts)), Some(("running", 2)))
        claim <- q.claimOf("rev-a")
        _ = assertEquals(claim.map(_.claimant), Some("w-b"))
        // a retryable failure backs off from the failure time; the third attempt is terminal
        failed <- q.fail("rev-a", "boom", t0)
        _ = assertEquals(
          failed.map(j => (j.status, j.attempts, j.nextAttemptAt)),
          Some(("pending", 2, Some(t0.plus(IngestionQueue.backoff(2)).toString)))
        )
        third <- q.claim(t0.plusSeconds(10), "w-c")
        _ = assertEquals(third.map(_.attempts), Some(3))
        last <- q.fail("rev-a", "boom", t0.plusSeconds(11))
      yield assertEquals(last.map(j => (j.status, j.nextAttemptAt)), Some(("failed", None)))
    }
  }
