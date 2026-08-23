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

  private def dockerAvailable: Boolean =
    scala.util.Try(org.testcontainers.DockerClientFactory.instance().isDockerAvailable).getOrElse(
      false
    )

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

  /** In-process control plane over a MinIO bucket, a data dir, and a plain HTTP client. */
  private def withS3(mode: IngestionMode = IngestionMode.Inline)(f: Env => IO[Unit]): IO[Unit] =
    IO(assume(dockerAvailable, "Docker is not available; skipping the MinIO suite")) *>
      (for
        cfg <- minio
        s3 <- ObjectStore.s3(cfg)
        _ <- Resource.eval(s3.ensureBucket)
        dir <- Files[IO].tempDirectory
        http <- EmberClientBuilder.default[IO].build
        stores = Server.localStores(dir, mode).copy(
          objects = s3,
          renditions = RenditionStore.of(s3, dir)
        )
        app <- Resource.eval(
          Server.build(dir, key, "http://test", owner, password, None, Nil, Some(stores))
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
  private def login(app: HttpApp[IO]): IO[String] =
    post(app, "/api/v1/auth/login", LoginRequest(owner, password), anon).map(r =>
      r.cookies.find(_.name == "np_session").get.content
    )
  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)
  private val assetIds = List("t1", "speech-effect", "speech-se", "speech-t", "speech-z")

  /** Follows one instruction with the plain client, exactly as `npub` does. */
  private def follow(http: Client[IO], m: UploadInstruction, body: Array[Byte]): IO[Status] =
    val req =
      Request[IO](Method.fromString(m.method).getOrElse(Method.PUT), Uri.unsafeFromString(m.url))
        .withEntity(body)
        .putHeaders(Headers(m.headers.toList.map((k, v) => Header.Raw(CIString(k), v))))
    http.run(req).use(r => r.body.compile.drain.as(r.status))

  private def negotiate(env: Env, auth: Auth, manifest: Array[Byte], parent: Option[String]) =
    for
      assets <- assetIds.traverse(id => bytes(s"reference/assets/$id.nii").map(id -> _))
      inv = assets.map((_, b) =>
        AssetInventory(Sha256.of(b).render, b.length.toLong, "application/x-nifti")
      )
      created <- post(
        env.app,
        s"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions",
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
        _ = assert(
          s.missing.forall(_.headers.keys.exists(_.equalsIgnoreCase("x-amz-checksum-sha256")))
        )
        _ = assert(s.manifestUrl.contains("X-Amz-Signature"))
        _ <- s.missing.traverse_ { m =>
          val body = assets.map(_._2).find(b => Sha256.of(b).render == m.digest).get
          follow(env.http, m, body).map(st => assert(st.isSuccess, s"PUT ${m.digest}: $st"))
        }
        _ <- assets.traverse_ { (_, b) =>
          env.store.stat(Sha256.of(b)).map(st =>
            assertEquals(st.map(_.size), Some(b.length.toLong))
          )
        }
        _ <- follow(env.http, UploadInstruction("m", s.manifestUrl), manifest).map(st =>
          assert(st.isSuccess, s"manifest PUT: $st")
        )
        committed <- commit(env, cookie(c), s.sessionId)
        _ = assertEquals(committed.status, Status.Created)
        r <- decode[CommitResult](committed)
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
      yield assertEquals(again.missing, Nil)
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
        // ...and if it did not, plant them under the digest key anyway: commit must still catch it
        _ <- env.store.putBlob(
          ObjectStore.key(Sha256.unsafe(t1.digest.stripPrefix("sha256:"))),
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
