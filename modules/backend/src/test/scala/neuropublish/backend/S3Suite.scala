package neuropublish.backend

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import fs2.io.file.{Files, Path}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.api.Stage4.given
import neuropublish.protocol.Sha256
import org.http4s.{Header, Headers, HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.*
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

/** S3 mode against MinIO (testcontainers). Skipped when Docker is unavailable. The control plane
  * runs in-process; objects travel over real HTTP between a plain client and the bucket.
  */
class S3Suite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 5.minutes
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val owner = "owner@example.org"
  private val password = "owner-dev-password"

  private val minio: Resource[IO, ObjectStore.S3Config] =
    Resource.make(IO.blocking {
      val c = GenericContainer(
        "minio/minio:latest",
        exposedPorts = Seq(9000),
        env = Map("MINIO_ROOT_USER" -> "minio", "MINIO_ROOT_PASSWORD" -> "minio-secret"),
        command = Seq("server", "/data"),
        waitStrategy = Wait.forHttp("/minio/health/live").forPort(9000)
      )
      c.start()
      c
    })(c => IO.blocking(c.stop())).map(c =>
      ObjectStore.S3Config(
        "np-test",
        Some(s"http://${c.host}:${c.mappedPort(9000)}"),
        "us-east-1",
        Some("minio"),
        Some("minio-secret"),
        pathStyle = true
      )
    )

  final case class Env(app: HttpApp[IO], store: ObjectStore.S3, http: Client[IO], data: Path)

  private val otherWs = ProjectKey("lab-b", "proj")
  private val otherOwner = "b@x.org"
  private val otherPassword = "pw-for-b"

  /** In-process control plane over a MinIO bucket, a data dir, and a plain HTTP client; two
    * workspaces, each with its own owner.
    */
  private def withS3(mode: IngestionMode = IngestionMode.Inline)(f: Env => IO[Unit]): IO[Unit] =
    IO(neuropublish.persistence.PgTestDatabase.requireDocker("the MinIO suite")) *>
      (for
        cfg <- minio
        s3 <- ObjectStore.s3(cfg)
        _ <- Resource.eval(s3.ensureBucket)
        dir <- Files[IO].tempDirectory
        http <- EmberClientBuilder.default[IO].build
        stores = Server.localStorage(dir, mode).copy(
          objects = s3,
          renditions = RenditionStore.of(s3, dir)
        )
        app <- Resource.eval(
          Server.build(
            dir,
            key,
            "http://test",
            owner,
            password,
            None,
            List(Server.Bootstrap(otherWs, otherOwner, otherPassword)),
            storage = Some(stores)
          )
        )
      yield Env(app.orNotFound, s3, http, dir)).use(f)

  private type Auth = Request[IO] => Request[IO]
  private def cookie(c: String): Auth = _.addCookie("np_session", c)
  private val anon: Auth = identity
  private def decode[A: io.circe.Decoder](r: org.http4s.Response[IO]): IO[A] =
    r.as[String].flatMap(b => IO.fromEither(io.circe.parser.decode[A](b)))
  private def get(app: HttpApp[IO], path: String, auth: Auth) =
    app.run(auth(Request[IO](Method.GET, Uri.unsafeFromString(path))))
  private def post[B: io.circe.Encoder](app: HttpApp[IO], path: String, body: B, auth: Auth) =
    app.run(auth(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body.asJson)))
  private def login(app: HttpApp[IO], email: String = owner, pw: String = password): IO[String] =
    post(app, "/api/v1/auth/login", LoginRequest(email, pw), anon).map(r =>
      r.cookies.find(_.name == "np_session").get.content
    )
  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)

  /** Follows one instruction with the plain client, exactly as `npub` does. */
  private def follow(http: Client[IO], m: UploadInstruction, body: Array[Byte]): IO[Status] =
    val req =
      Request[IO](Method.fromString(m.method).getOrElse(Method.PUT), Uri.unsafeFromString(m.url))
        .withEntity(body)
        .putHeaders(Headers(m.headers.toList.map((k, v) => Header.Raw(CIString(k), v))))
    http.run(req).use(r => r.body.compile.drain.as(r.status))

  private def negotiate(
      env: Env,
      auth: Auth,
      manifest: Array[Byte],
      parent: Option[String],
      project: ProjectKey = key
  ) =
    for
      assets <- ReferenceBundle.assets.traverse((id, file, _) =>
        bytes(s"reference/assets/$file").map(id -> _)
      )
      inv = assets.zip(ReferenceBundle.assets).map((e, a) =>
        AssetInventory(Sha256.of(e._2).render, e._2.length.toLong, a._3)
      )
      created <- post(
        env.app,
        s"/api/v1/workspaces/${project.workspace}/projects/${project.project}/upload-sessions",
        CreateUploadSession(Sha256.of(manifest).render, manifest.length.toLong, parent, inv),
        auth
      )
      _ = assertEquals(created.status, Status.Created)
      s <- decode[UploadSessionCreated](created)
    yield (s, assets)

  private def commit(env: Env, auth: Auth, session: String) =
    post(env.app, s"/api/v1/upload-sessions/$session/commit", CommitRequest(Some("s3")), auth)

  test("presigned PUTs land in the bucket, HEAD sizes match, commit verifies and renders") {
    withS3() { env =>
      for
        c <- login(env.app)
        manifest <- bytes("reference/manifest.json")
        (s, assets) <- negotiate(env, cookie(c), manifest, None)
        _ = assertEquals(s.missing.length, assets.length)
        _ = assert(s.missing.forall(_.url.contains("X-Amz-Signature")), s.missing.map(_.url))
        // signed PUTs address the session's staging area, never a committed key
        _ = assert(
          s.missing.forall(_.url.contains(s"/staging/${s.sessionId}/")),
          s.missing.map(_.url)
        )
        _ = assert(s.missing.forall(m => !m.url.contains("/sha256/")), s.missing.map(_.url))
        _ = assert(s.manifestUrl.contains(s"/staging/${s.sessionId}/"), s.manifestUrl)
        _ = assert(
          s.missing.forall(_.headers.keys.exists(_.equalsIgnoreCase("x-amz-checksum-sha256")))
        )
        _ = assert(s.manifestUrl.contains("X-Amz-Signature"))
        _ <- s.missing.traverse_ { m =>
          val body = assets.map(_._2).find(b => Sha256.of(b).render == m.digest).get
          follow(env.http, m, body).map(st => assert(st.isSuccess, s"PUT ${m.digest}: $st"))
        }
        // staged, not committed: a half-finished upload is invisible to readers
        _ <- assets.traverse_ { (_, b) =>
          env.store.stagedStat(s.sessionId, Sha256.of(b)).map(st =>
            assertEquals(st.map(_.size), Some(b.length.toLong))
          ) *> env.store.exists(Sha256.of(b)).map(x => assert(!x, "not committed yet"))
        }
        // a refresh re-issues nothing for staged objects
        refreshed <- get(env.app, s"/api/v1/upload-sessions/${s.sessionId}", cookie(c)).flatMap(
          decode[UploadSessionCreated]
        )
        _ = assertEquals(refreshed.missing, Nil)
        _ <- follow(env.http, UploadInstruction("m", s.manifestUrl), manifest).map(st =>
          assert(st.isSuccess, s"manifest PUT: $st")
        )
        committed <- commit(env, cookie(c), s.sessionId)
        _ = assertEquals(committed.status, Status.Created)
        _ = assertEquals(
          committed.headers.get(org.typelevel.ci.CIString("Cache-Control")).map(_.head.value),
          Some("no-store")
        )
        r <- decode[CommitResult](committed)
        _ <- assets.traverse_ { (_, b) =>
          env.store.stat(Sha256.of(b)).map(st =>
            assertEquals(st.map(_.size), Some(b.length.toLong))
          )
        }
        staging <- env.store.listBlobs(s"staging/${s.sessionId}/").compile.toList
        _ = assertEquals(staging, Nil, "staging is cleared after commit")
        detail <- get(env.app, s"/api/v1/revisions/${r.revisionId}", cookie(c)).flatMap(
          decode[RevisionDetail]
        )
        _ = assertEquals(detail.renditions.map(_.status).distinct, List("ready"))
        _ = assertEquals(detail.ingestion.map(_.status), Some("ready"))
        _ = assert(detail.renditions.forall(_.payloadUrl.contains("X-Amz-Signature")))
        // renditions come straight from the bucket, never through the control plane
        hdr <- env.http.expect[String](detail.renditions.head.headerUrl)
        _ = assert(hdr.contains("volume-f32"))
        viaApi <- get(
          env.app,
          s"/api/v1/revisions/${r.revisionId}/renditions/speech-t/payload",
          cookie(c)
        )
        _ = assertEquals(viaApi.status, Status.TemporaryRedirect)
        _ = assert(viaApi.headers.get[org.http4s.headers.Location].get.uri.renderString.contains(
          "X-Amz-Signature"
        ))
        // the next session reports nothing missing: the workspace owns every digest now
        (again, _) <- negotiate(env, cookie(c), manifest, Some(r.revisionId))
        _ = assertEquals(again.missing, Nil)
        // ...but the identical bytes are still "missing" for another workspace (ADR 0004)
        cb <- login(env.app, otherOwner, otherPassword)
        (other, _) <- negotiate(env, cookie(cb), manifest, None, otherWs)
        _ = assertEquals(other.missing.length, assets.length)
        _ = assert(other.missing.forall(_.url.contains(s"/staging/${other.sessionId}/")))
      yield ()
    }
  }

  test("a tampered signed PUT is refused, and no signed URL can write a committed key") {
    withS3() { env =>
      for
        c <- login(env.app)
        manifest <- bytes("reference/manifest.json")
        (s, assets) <- negotiate(env, cookie(c), manifest, None)
        t1 = assets.find(_._1 == "t1").get._2
        m = s.missing.find(_.digest == Sha256.of(t1).render).get
        // body of another length: Content-Length is signed
        // (the client sends its real Content-Length; the signed one differs)
        shorter <- follow(
          env.http,
          m.copy(headers = m.headers.filterNot(_._1.equalsIgnoreCase("content-length"))),
          t1.dropRight(10)
        )
        _ = assert(!shorter.isSuccess, s"length tamper accepted: $shorter")
        // same length, different bytes: the checksum header is signed and the provider checks it
        wrong = t1.clone()
        _ = wrong(100) = (wrong(100) + 1).toByte
        altered <- follow(env.http, m, wrong)
        _ = assert(!altered.isSuccess, s"body tamper accepted: $altered")
        // a different media type: signed as well
        retyped <- follow(
          env.http,
          m.copy(headers =
            m.headers.map((k, v) =>
              k -> (if k.equalsIgnoreCase("content-type") then "text/plain" else v)
            )
          ),
          t1
        )
        _ = assert(!retyped.isSuccess, s"content-type tamper accepted: $retyped")
        // the signed URL cannot be bent to a committed key
        committedKey =
          m.url.replace(s"/staging/${s.sessionId}/", "/sha256/" + Sha256.of(t1).hex.take(2) + "/")
        forged <- follow(env.http, m.copy(url = committedKey), t1)
        _ = assert(!forged.isSuccess, s"forged key accepted: $forged")
        planted <- env.store.exists(Sha256.of(t1))
      yield assert(!planted)
    }
  }

  test("a stored manifest whose bytes no longer hash to the record's digest is refused on read") {
    withS3(IngestionMode.Worker) { env =>
      for
        c <- login(env.app)
        manifest <- bytes("reference/manifest.json")
        (s, assets) <- negotiate(env, cookie(c), manifest, None)
        _ <- s.missing.traverse_(m =>
          follow(env.http, m, assets.map(_._2).find(b => Sha256.of(b).render == m.digest).get)
        )
        _ <- follow(env.http, UploadInstruction("m", s.manifestUrl), manifest)
        r <- commit(env, cookie(c), s.sessionId).flatMap(decode[CommitResult])
        // worker mode: committed, renditions pending
        pending <- get(
          env.app,
          s"/api/v1/revisions/${r.revisionId}",
          cookie(c)
        ).flatMap(decode[RevisionDetail])
        _ = assertEquals(pending.ingestion.map(_.status), Some("pending"))
        // an operator-level overwrite of the committed manifest object (no client can do this)
        md = Sha256.unsafe(r.digest.stripPrefix("sha256:"))
        _ <- env.store.putBlob(
          ObjectStore.key(md),
          "{\"tampered\":true}".getBytes("UTF-8"),
          "application/json"
        )
        read <- get(env.app, s"/api/v1/revisions/${r.revisionId}", cookie(c))
        _ = assertEquals(read.status, Status.ServiceUnavailable)
        e <- decode[ApiError](read)
        _ = assertEquals(e.code, "integrity")
        // what the worker reads goes through the same check
        revisions <- LocalRevisionStore(env.data)
        rec <- revisions.resolveId(r.revisionId).map(_.get)
        refused <- Derivation.manifestOf(env.store, rec).attempt
      yield assert(refused.left.exists(_.isInstanceOf[IntegrityError]), refused.toString)
    }
  }

  test("bytes that do not hash to the declared digest are rejected at commit") {
    withS3() { env =>
      for
        c <- login(env.app)
        manifest <- bytes("reference/manifest.json")
        (s, assets) <- negotiate(env, cookie(c), manifest, None)
        wrong = assets.find(_._1 == "t1").get._2.clone()
        _ = wrong(100) = (wrong(100) + 1).toByte
        t1 = s.missing.find(_.digest == Sha256.of(assets.find(_._1 == "t1").get._2).render).get
        // the signed PUT carries the checksum, so the provider itself may refuse the bytes...
        st <- follow(env.http, t1, wrong)
        // ...and if it did not, plant them in the session's staging area anyway: commit hashes
        // every staged object (an S3-compatible endpoint's checksum echo is not trusted)
        _ <- env.store.putBlob(
          env.store.stagingKey(s.sessionId, Sha256.unsafe(t1.digest.stripPrefix("sha256:"))),
          wrong,
          "application/octet-stream"
        )
        _ <- s.missing.filterNot(_ == t1).traverse_ { m =>
          follow(env.http, m, assets.map(_._2).find(b => Sha256.of(b).render == m.digest).get)
        }
        _ <- follow(env.http, UploadInstruction("m", s.manifestUrl), manifest)
        committed <- commit(env, cookie(c), s.sessionId)
        _ = assertEquals(committed.status, Status.BadRequest, s"provider PUT status was $st")
        e <- decode[ApiError](committed)
        _ = assert(e.message.contains("t1"), e.message)
        head <- get(env.app, "/api/v1/workspaces/rotman/projects/sherlock", cookie(c)).flatMap(
          decode[ProjectSummary]
        )
      yield assertEquals(head.head, None)
    }
  }

  test("share rendition routes redirect to a presigned GET only for the shared revision") {
    withS3() { env =>
      for
        c <- login(env.app)
        manifest <- bytes("reference/manifest.json")
        (s, assets) <- negotiate(env, cookie(c), manifest, None)
        _ <- s.missing.traverse_(m =>
          follow(env.http, m, assets.map(_._2).find(b => Sha256.of(b).render == m.digest).get)
        )
        _ <- follow(env.http, UploadInstruction("m", s.manifestUrl), manifest)
        r <- commit(env, cookie(c), s.sessionId).flatMap(decode[CommitResult])
        v <- post(
          env.app,
          s"/api/v1/revisions/${r.revisionId}/views",
          SaveView("shared", Json.obj("layers" -> Json.arr())),
          cookie(c)
        ).flatMap(decode[SavedViewDetail])
        link <- post(
          env.app,
          s"/api/v1/views/${v.id}/versions/1/links",
          CreateShareLink(Some(7)),
          cookie(c)
        ).flatMap(decode[ShareLinkCreated])
        shared <- get(env.app, s"/api/v1/share/${link.secret}", anon).flatMap(decode[SharedView])
        _ = assert(shared.revision.renditions.forall(_.payloadUrl.contains("X-Amz-Signature")))
        _ = assert(
          shared.revision.renditions.forall(_.payloadUrl.contains(s"renditions/${r.revisionId}/"))
        )
        redirect <- get(env.app, s"/api/v1/share/${link.secret}/renditions/speech-t/header", anon)
        _ = assertEquals(redirect.status, Status.TemporaryRedirect)
        loc = redirect.headers.get[org.http4s.headers.Location].get.uri.renderString
        _ = assert(loc.contains(s"renditions/${r.revisionId}/speech-t.json"), loc)
        body <- env.http.expect[String](loc)
        _ = assert(body.contains("volume-f32"))
        other <- get(env.app, s"/api/v1/share/${link.secret}/renditions/nope/header", anon)
        _ = assertEquals(other.status, Status.NotFound)
        bogus <- get(env.app, s"/api/v1/share/${"x" * 32}/renditions/speech-t/header", anon)
      yield assertEquals(bogus.status, Status.Unauthorized)
    }
  }
