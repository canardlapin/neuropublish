package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.syntax.*
import java.time.Instant
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.protocol.Sha256
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import scala.concurrent.duration.*

/** Orphan cleanup over the local store: unreferenced and old → deleted; referenced, or declared by
  * a young session, or young itself → kept; dry run touches nothing.
  */
class GcSuite extends CatsEffectSuite:
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val token = "t"

  final case class Env(app: HttpApp[IO], stores: Server.Stores, data: Path)
  private val env = ResourceFunFixture(
    Files[IO].tempDirectory.evalMap { dir =>
      val stores = Server.localStores(dir)
      Server.build(dir, key, "http://test", legacyToken = Some(token), stores = Some(stores))
        .map(r => Env(r.orNotFound, stores, dir))
    }
  )
  private def auth(r: Request[IO]) =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)

  private def push(app: HttpApp[IO]): IO[CommitResult] =
    for
      manifest <- bytes("reference/manifest.json")
      assets <- List("t1", "speech-effect", "speech-se", "speech-t", "speech-z").traverse(id =>
        bytes(s"reference/assets/$id.nii")
      )
      inv =
        assets.map(b => AssetInventory(Sha256.of(b).render, b.length.toLong, "application/x-nifti"))
      s <- app.run(auth(Request[IO](
        Method.POST,
        uri"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions"
      ).withEntity(CreateUploadSession(
        Sha256.of(manifest).render,
        manifest.length.toLong,
        None,
        inv
      ).asJson)))
        .flatMap(_.as[String]).flatMap(b =>
          IO.fromEither(io.circe.parser.decode[UploadSessionCreated](b))
        )
      _ <- assets.traverse_(b =>
        app.run(auth(Request[IO](
          Method.PUT,
          Uri.unsafeFromString(
            s"/api/v1/upload-sessions/${s.sessionId}/objects/${Sha256.of(b).render}"
          )
        ).withEntity(b)))
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
      r <- c.as[String].flatMap(b => IO.fromEither(io.circe.parser.decode[CommitResult](b)))
    yield r

  private def gc(e: Env, dryRun: Boolean, now: Instant, olderThan: FiniteDuration = 24.hours) =
    Audit.localFs(e.data).flatMap(a =>
      Gc.run(
        e.data,
        e.stores.objects,
        e.stores.renditions,
        e.stores.sessions,
        a,
        olderThan,
        dryRun,
        now
      )
    )

  env.test("unreferenced old objects go, referenced ones stay, dry run removes nothing") { e =>
    val orphan = Array.fill[Byte](1000)(3)
    val od = Sha256.of(orphan)
    for
      r <- push(e.app)
      _ <- e.stores.objects.put(od, orphan).map(x => assert(x.isRight))
      before <- e.stores.objects.list.compile.toList
      _ = assertEquals(before.length, 7) // 5 assets + manifest + orphan
      tomorrow <- IO.realTimeInstant.map(_.plusSeconds(2 * 86400))
      dry <- gc(e, dryRun = true, tomorrow)
      _ = assertEquals(dry.deleted.map(_.hex), List(od.hex))
      _ <- e.stores.objects.exists(od).map(assert(_, "dry run must not delete"))
      young <- gc(e, dryRun = false, Instant.now()) // nothing is older than 24 h yet
      _ = assertEquals(young.deleted, Nil)
      _ <- e.stores.objects.exists(od).map(assert(_, "a young object is protected"))
      real <- gc(e, dryRun = false, tomorrow)
      _ = assertEquals(real.deleted.map(_.hex), List(od.hex))
      _ = assertEquals(real.referenced, 6)
      _ <- e.stores.objects.exists(od).map(x => assert(!x, "orphan must be deleted"))
      _ <- e.stores.objects.exists(Sha256.unsafe(r.digest.stripPrefix("sha256:"))).map(assert(_))
      after <- e.stores.objects.list.compile.toList
      _ = assertEquals(after.length, 6)
      audit <- Audit.localFs(e.data).flatMap(_.list("rotman"))
    yield assert(audit.exists(_.action == "gc"), audit.map(_.action))
  }

  env.test("an object declared by an unfinished young session is kept; a stale one is not") { e =>
    val blob = Array.fill[Byte](10)(9)
    val d = Sha256.of(blob)
    for
      _ <- e.stores.objects.put(d, blob)
      s <- e.app.run(auth(Request[IO](
        Method.POST,
        uri"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions"
      ).withEntity(CreateUploadSession(
        "sha256:" + "0" * 64,
        1,
        None,
        List(
          AssetInventory(d.render, 10, "application/octet-stream")
        )
      ).asJson)))
      _ = assertEquals(s.status, Status.Created)
      // age the object: it is old, but the session that declared it is young
      _ <- IO.blocking(java.nio.file.Files.setLastModifiedTime(
        (e.data / "objects" / "sha256" / d.hex.take(2) / d.hex).toNioPath,
        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3 * 86400 * 1000L)
      ))
      now <- IO.realTimeInstant
      r1 <- gc(e, dryRun = false, now)
      _ = assertEquals(r1.deleted, Nil)
      later <- IO.realTimeInstant.map(_.plusSeconds(2 * 86400))
      r2 <- gc(e, dryRun = false, later)
    yield assertEquals(r2.deleted.map(_.hex), List(d.hex))
  }
