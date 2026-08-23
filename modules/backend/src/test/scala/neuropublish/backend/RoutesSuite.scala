package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.syntax.*
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.protocol.Sha256
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}
import org.http4s.implicits.*

/** Stage 1 exit criteria, exercised against the routes in-process; parameterized over the record
  * stores ([[RoutesSuite]] local fs, `PgRoutesSuite` PostgreSQL).
  */
abstract class RoutesSpec(factory: ServerFactory) extends CatsEffectSuite:
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val token = "t"

  private def server = ResourceFunFixture(
    factory.build(key, "http://test", legacyToken = Some(token)).map(_.app)
  )

  private def auth(r: Request[IO]) =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)

  /** Runs the whole publisher flow; returns the commit response. */
  private def push(
      app: org.http4s.HttpApp[IO],
      parent: Option[String],
      substitute: Option[(String, Array[Byte])] = None
  ) =
    for
      manifest <- bytes("reference/manifest.json")
      assets <- List("t1", "speech-effect", "speech-se", "speech-t", "speech-z").traverse(id =>
        bytes(s"reference/assets/$id.nii").map(id -> _)
      )
      inv = assets.map((_, b) =>
        AssetInventory(Sha256.of(b).render, b.length.toLong, "application/x-nifti")
      )
      created <- app.run(auth(Request[IO](
        Method.POST,
        uri"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions"
      )
        .withEntity(CreateUploadSession(
          Sha256.of(manifest).render,
          manifest.length.toLong,
          parent,
          inv
        ).asJson)))
      _ = assertEquals(created.status, Status.Created)
      s <- created.as[io.circe.Json].map(_.as[UploadSessionCreated].toOption.get)
      _ <- assets.traverse_ { (id, b) =>
        val body = substitute.filter(_._1 == id).map(_._2).getOrElse(b)
        app.run(auth(Request[IO](
          Method.PUT,
          Uri.unsafeFromString(
            s"/api/v1/upload-sessions/${s.sessionId}/objects/${Sha256.of(b).render}"
          )
        ).withEntity(body)))
          .map(r => (id, r.status))
      }
      _ <- app.run(auth(Request[IO](
        Method.PUT,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/manifest")
      ).withEntity(manifest)))
      commit <- app.run(auth(Request[IO](
        Method.POST,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/commit")
      ).withEntity(CommitRequest(Some("first")).asJson)))
    yield commit

  server.test("push commits a revision whose digest is sha256(manifest bytes) and renders it") {
    app =>
      for
        manifest <- bytes("reference/manifest.json")
        c <- push(app, None)
        _ = assertEquals(c.status, Status.Created)
        r <- c.as[io.circe.Json].map(_.as[CommitResult].toOption.get)
        _ = assertEquals(r.digest, Sha256.of(manifest).render)
        detail <- app.run(auth(Request[IO](
          Method.GET,
          Uri.unsafeFromString(s"/api/v1/revisions/${r.revisionId}")
        ))).flatMap(_.as[String]).flatMap(b =>
          IO.fromEither(io.circe.parser.decode[RevisionDetail](b))
        )
        _ = assertEquals(detail.renditions.map(_.status).distinct, List("ready"))
        _ = assertEquals(
          detail.renditions.map(_.assetId).sorted,
          List("speech-effect", "speech-se", "speech-t", "speech-z", "t1")
        )
        hdr <- app.run(auth(Request[IO](
          Method.GET,
          Uri.unsafeFromString(s"/api/v1/revisions/${r.revisionId}/renditions/speech-t/header")
        ))).flatMap(_.as[String])
        _ = assert(hdr.contains("volume-f32"))
        proj <- app.run(auth(Request[IO](
          Method.GET,
          uri"/api/v1/workspaces/rotman/projects/sherlock"
        ))).flatMap(_.as[io.circe.Json]).map(_.as[ProjectSummary].toOption.get)
      yield assertEquals(proj.head, Some(r.revisionId))
  }

  server.test("a second push with a stale parent is rejected with the current head") { app =>
    for
      c1 <- push(app, None)
      r1 <- c1.as[io.circe.Json].map(_.as[CommitResult].toOption.get)
      c2 <- push(app, None) // same (absent) parent again
      _ = assertEquals(c2.status, Status.Conflict)
      e <- c2.as[io.circe.Json].map(_.as[ApiError].toOption.get)
      _ = assertEquals(e.head, Some(r1.revisionId))
      c3 <- push(app, Some(r1.revisionId))
    yield assertEquals(c3.status, Status.Created)
  }

  server.test("a substituted asset fails before commit") { app =>
    for
      good <- bytes("reference/assets/speech-z.nii")
      c <- push(app, None, substitute = Some("speech-t" -> good))
      _ = assertEquals(c.status, Status.BadRequest)
      e <- c.as[io.circe.Json].map(_.as[ApiError].toOption.get)
    yield assert(
      e.message.contains("was not uploaded") || e.message.contains("digest mismatch"),
      e.message
    )
  }

  server.test("unauthenticated mutation and reads are refused (projects are private, Stage 4)") {
    app =>
      for
        r <- app.run(Request[IO](
          Method.POST,
          uri"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions"
        )
          .withEntity(CreateUploadSession("sha256:" + "0" * 64, 1, None, Nil).asJson))
        _ = assertEquals(r.status, Status.Unauthorized)
        p <- app.run(Request[IO](Method.GET, uri"/api/v1/workspaces/rotman/projects/sherlock"))
        _ = assertEquals(p.status, Status.Unauthorized)
        legacy <- app.run(auth(Request[IO](
          Method.GET,
          uri"/api/v1/workspaces/rotman/projects/sherlock"
        )))
      yield assertEquals(legacy.status, Status.Ok)
  }

  server.test("a substituted object is rejected at upload and the head never moves") { app =>
    for
      c <- push(app, None, substitute = Some("t1" -> Array.fill[Byte](54112)(7)))
      _ = assertEquals(c.status, Status.BadRequest)
      proj <- app.run(auth(Request[IO](
        Method.GET,
        uri"/api/v1/workspaces/rotman/projects/sherlock"
      ))).flatMap(_.as[io.circe.Json]).map(_.as[ProjectSummary].toOption.get)
    yield assertEquals(proj.head, None)
  }

  server.test("path parameters that are not ids never reach the filesystem") { app =>
    for
      r1 <- app.run(auth(Request[IO](
        Method.GET,
        Uri.unsafeFromString("/api/v1/revisions/..%2Fprojects%2Frotman/renditions/sherlock/header")
      )))
      _ = assertEquals(r1.status, Status.NotFound)
      r2 <- app.run(auth(Request[IO](
        Method.GET,
        Uri.unsafeFromString("/api/v1/revisions/..%2F..%2Fetc%2Fpasswd")
      )))
    yield assertEquals(r2.status, Status.NotFound)
  }

  test("OpenAPI document lists the publication endpoints") {
    assert(Routes.openApiYaml.contains("/upload-sessions"))
    assert(Routes.openApiYaml.contains("/revisions/{revision}"))
  }

class RoutesSuite extends RoutesSpec(ServerFactory.Local)
