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
      substitute: Option[(String, Array[Byte])] = None,
      skip: Option[String] = None
  ) =
    for
      manifest <- bytes("reference/manifest.json")
      assets <- ReferenceBundle.assets.traverse((id, file, _) =>
        bytes(s"reference/assets/$file").map(id -> _)
      )
      inv = assets.zip(ReferenceBundle.assets).map((e, a) =>
        AssetInventory(Sha256.of(e._2).render, e._2.length.toLong, a._3)
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
      _ <- assets.filterNot((id, _) => skip.contains(id)).traverse_ { (id, b) =>
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
        _ = assertEquals(detail.renditions.map(_.assetId).sorted, ReferenceBundle.ids.sorted)
        _ = assertEquals(
          detail.renditions.map(r => (r.assetId, r.kind, r.surface)).filter(_._2 != "volume"),
          List(
            ("lh-pial", "surface-mesh", None),
            ("rh-pial", "surface-mesh", None),
            ("speech-t-lh", "vertex-field", Some("lh-pial-surface")),
            ("speech-t-rh", "vertex-field", Some("rh-pial-surface")),
            ("speech-z-lh", "vertex-field", Some("lh-pial-surface")),
            ("speech-z-rh", "vertex-field", Some("rh-pial-surface"))
          )
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

  server.test("a substituted asset is refused at upload, so commit finds it never uploaded") {
    app =>
      for
        good <- bytes("reference/assets/speech-z.nii")
        c <- push(app, None, substitute = Some("speech-t" -> good))
        _ = assertEquals(c.status, Status.BadRequest)
        e <- c.as[io.circe.Json].map(_.as[ApiError].toOption.get)
      yield
        assert(e.message.contains("asset speech-t"), e.message)
        assert(e.message.contains("was not uploaded"), e.message)
  }

  server.test(
    "a never-uploaded asset fails commit as 'was not uploaded'; the PUT of wrong bytes says 'digest mismatch'"
  ) {
    app =>
      for
        c <- push(app, None, skip = Some("speech-se"))
        _ = assertEquals(c.status, Status.BadRequest)
        e <- c.as[io.circe.Json].map(_.as[ApiError].toOption.get)
        _ = assert(e.message.contains("asset speech-se"), e.message)
        _ = assert(e.message.contains("was not uploaded"), e.message)
        _ = assert(!e.message.contains("digest mismatch"), e.message)
        // the substituted PUT itself is the place that names the mismatch
        manifest <- bytes("reference/manifest.json")
        t1 <- bytes("reference/assets/speech-se.nii") // never uploaded above: not yet registered
        created <- app.run(auth(Request[IO](
          Method.POST,
          uri"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions"
        ).withEntity(CreateUploadSession(
          Sha256.of(manifest).render,
          manifest.length.toLong,
          None,
          List(AssetInventory(Sha256.of(t1).render, t1.length.toLong, "application/x-nifti"))
        ).asJson)))
        s <- created.as[io.circe.Json].map(_.as[UploadSessionCreated].toOption.get)
        put <- app.run(auth(Request[IO](
          Method.PUT,
          Uri.unsafeFromString(
            s"/api/v1/upload-sessions/${s.sessionId}/objects/${Sha256.of(t1).render}"
          )
        ).withEntity(Array.fill[Byte](t1.length)(7))))
        _ = assertEquals(put.status, Status.BadRequest)
        pe <- put.as[io.circe.Json].map(_.as[ApiError].toOption.get)
        // and the session can be re-read for what is still missing
        again <- app.run(auth(Request[IO](
          Method.GET,
          Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}")
        )))
        _ = assertEquals(again.status, Status.Ok)
        refreshed <- again.as[io.circe.Json].map(_.as[UploadSessionCreated].toOption.get)
      yield
        assert(pe.message.contains("digest mismatch"), pe.message)
        assertEquals(refreshed.missing.map(_.digest), List(Sha256.of(t1).render))
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
