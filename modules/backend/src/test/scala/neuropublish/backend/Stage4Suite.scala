package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.api.Stage4.given
import neuropublish.protocol.Sha256
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import scala.concurrent.duration.*

/** Stage 4 exit criteria: identity, device flow, credentials, saved views, share links, provenance,
  * audit — against the routes in-process, with no static token configured.
  */
class Stage4Suite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 2.minutes
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val owner = "owner@example.org"
  private val password = "owner-dev-password"

  private def server = ResourceFunFixture(
    Files[IO].tempDirectory.evalMap(dir =>
      Server.build(dir, key, "http://test", owner, password, legacyToken = None).map(_.orNotFound)
    )
  )

  private type Auth = Request[IO] => Request[IO]
  private def bearer(t: String): Auth =
    _.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t)))
  private def cookie(c: String): Auth = _.addCookie("np_session", c)
  private val anon: Auth = identity

  private def decode[A: io.circe.Decoder](r: org.http4s.Response[IO]): IO[A] =
    r.as[String].flatMap(b => IO.fromEither(io.circe.parser.decode[A](b)))
  private def get(app: HttpApp[IO], path: String, auth: Auth) =
    app.run(auth(Request[IO](Method.GET, Uri.unsafeFromString(path))))
  private def post[B: io.circe.Encoder](app: HttpApp[IO], path: String, body: B, auth: Auth) =
    app.run(auth(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body.asJson)))
  private def put[B: io.circe.Encoder](app: HttpApp[IO], path: String, body: B, auth: Auth) =
    app.run(auth(Request[IO](Method.PUT, Uri.unsafeFromString(path)).withEntity(body.asJson)))
  private def delete(app: HttpApp[IO], path: String, auth: Auth) =
    app.run(auth(Request[IO](Method.DELETE, Uri.unsafeFromString(path))))

  /** Sign in; returns the session cookie value. */
  private def login(app: HttpApp[IO], email: String = owner, pw: String = password): IO[String] =
    post(app, "/api/v1/auth/login", LoginRequest(email, pw), anon).map { r =>
      assertEquals(r.status, Status.Ok)
      val c = r.cookies.find(_.name == "np_session").get
      assert(c.httpOnly, "session cookie must be HttpOnly")
      assertEquals(c.sameSite.map(_.toString.toLowerCase), Some("lax"))
      c.content
    }

  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)

  /** The whole publisher flow under `auth`; returns the commit response. */
  private def push(app: HttpApp[IO], auth: Auth, parent: Option[String] = None) =
    for
      manifest <- bytes("reference/manifest.json")
      assets <- List("t1", "speech-effect", "speech-se", "speech-t", "speech-z").traverse(id =>
        bytes(s"reference/assets/$id.nii").map(id -> _)
      )
      inv = assets.map((_, b) =>
        AssetInventory(Sha256.of(b).render, b.length.toLong, "application/x-nifti")
      )
      created <- post(
        app,
        "/api/v1/workspaces/rotman/projects/sherlock/upload-sessions",
        CreateUploadSession(Sha256.of(manifest).render, manifest.length.toLong, parent, inv),
        auth
      )
      _ = assertEquals(created.status, Status.Created)
      s <- decode[UploadSessionCreated](created)
      _ <- assets.traverse_ { (_, b) =>
        app.run(auth(Request[IO](
          Method.PUT,
          Uri.unsafeFromString(
            s"/api/v1/upload-sessions/${s.sessionId}/objects/${Sha256.of(b).render}"
          )
        ).withEntity(b))).map(r => assertEquals(r.status, Status.NoContent))
      }
      _ <- app.run(auth(Request[IO](
        Method.PUT,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/manifest")
      ).withEntity(manifest))).map(r => assertEquals(r.status, Status.NoContent))
      commit <- post(
        app,
        s"/api/v1/upload-sessions/${s.sessionId}/commit",
        CommitRequest(Some("stage 4")),
        auth
      )
    yield commit

  private def pushed(app: HttpApp[IO], auth: Auth): IO[CommitResult] =
    push(app, auth).flatMap { c =>
      assertEquals(c.status, Status.Created)
      decode[CommitResult](c)
    }

  // ---------------------------------------------------------------- identity

  server.test("login sets a session whose `me` names the bootstrap owner; wrong password is 401") {
    app =>
      for
        c <- login(app)
        me <- get(app, "/api/v1/auth/me", cookie(c)).flatMap(decode[Me])
        _ = assertEquals(me.user.email, owner)
        _ = assertEquals(me.memberships, List(Membership("rotman", "owner")))
        bad <- post(app, "/api/v1/auth/login", LoginRequest(owner, "nope"), anon)
        _ = assertEquals(bad.status, Status.Unauthorized)
        nobody <- get(app, "/api/v1/auth/me", anon)
        _ = assertEquals(nobody.status, Status.Unauthorized)
        out <- post(app, "/api/v1/auth/logout", Json.obj(), cookie(c))
        _ = assertEquals(out.status, Status.NoContent)
        after <- get(app, "/api/v1/auth/me", cookie(c))
      yield assertEquals(after.status, Status.Unauthorized)
  }

  private def serverWithDir = ResourceFunFixture(
    Files[IO].tempDirectory.evalMap(dir =>
      Server.build(dir, key, "http://test", owner, password, legacyToken = None)
        .map(r => (dir, r.orNotFound))
    )
  )

  serverWithDir.test(
    "secrets on disk are hashes: no clear password, session, token, or link secret"
  ) {
    (dir, app) =>
      for
        c <- login(app)
        codes <- post(app, "/api/v1/auth/device", DeviceStart("npub"), anon).flatMap(
          decode[DeviceCodes]
        )
        _ <- post(app, "/api/v1/auth/device/approve", DeviceApprove(codes.userCode), cookie(c))
        tok <- post(app, "/api/v1/auth/device/token", DevicePoll(codes.deviceCode), anon).flatMap(
          decode[DeviceToken]
        )
        cred <- post(
          app,
          "/api/v1/workspaces/rotman/projects/sherlock/credentials",
          CreateCredential("batch"),
          cookie(c)
        ).flatMap(decode[CredentialCreated])
        r <- pushed(app, cookie(c))
        v <- post(
          app,
          s"/api/v1/revisions/${r.revisionId}/views",
          SaveView("shared", Json.obj()),
          cookie(c)
        ).flatMap(decode[SavedViewDetail])
        link <- post(
          app,
          s"/api/v1/views/${v.id}/versions/1/links",
          CreateShareLink(None),
          cookie(c)
        ).flatMap(decode[ShareLinkCreated])
        texts <- Files[IO].walk(dir).filter(p =>
          p.toString.endsWith(".json") || p.toString.endsWith(".jsonl")
        ).evalMap(p => Files[IO].readUtf8(p).compile.string).compile.toList
        all = texts.mkString("\n")
        _ = assert(!all.contains(password), "clear password on disk")
        _ = assert(!all.contains(c), "session secret on disk")
        _ = assert(!all.contains(tok.token.get), "user token on disk")
        _ = assert(!all.contains(cred.secret), "credential secret on disk")
        _ = assert(!all.contains(link.secret), "link secret on disk")
        userFiles <- Files[IO].list(dir / "users").filter(_.toString.endsWith(".json"))
          .evalMap(p => Files[IO].readUtf8(p).compile.string).compile.toList
      yield assert(userFiles.exists(_.contains("pbkdf2-hmac-sha256")), userFiles)
  }

  server.test("device flow: start, poll pending, approve from a session, poll granted, push") {
    app =>
      for
        codes <- post(app, "/api/v1/auth/device", DeviceStart("npub"), anon).flatMap(
          decode[DeviceCodes]
        )
        _ = assert(codes.userCode.matches("[A-Z2-9]{4}-[A-Z2-9]{4}"), codes.userCode)
        _ = assertEquals(codes.interval, 5)
        p1 <- post(app, "/api/v1/auth/device/token", DevicePoll(codes.deviceCode), anon).flatMap(
          decode[DeviceToken]
        )
        _ = assertEquals(p1.status, "pending")
        // approving needs a browser session
        noSession <- post(app, "/api/v1/auth/device/approve", DeviceApprove(codes.userCode), anon)
        _ = assertEquals(noSession.status, Status.Unauthorized)
        c <- login(app)
        ok <- post(
          app,
          "/api/v1/auth/device/approve",
          DeviceApprove(codes.userCode.toLowerCase),
          cookie(c)
        )
        _ = assertEquals(ok.status, Status.NoContent)
        // polling faster than the interval is still "pending"
        p2 <- post(app, "/api/v1/auth/device/token", DevicePoll(codes.deviceCode), anon).flatMap(
          decode[DeviceToken]
        )
        _ = assertEquals(p2.status, "pending")
        _ <- IO.sleep(5.2.seconds)
        p3 <- post(app, "/api/v1/auth/device/token", DevicePoll(codes.deviceCode), anon).flatMap(
          decode[DeviceToken]
        )
        _ = assertEquals(p3.status, "granted")
        _ = assertEquals(p3.tokenType, Some("user"))
        _ = assertEquals(p3.user.map(_.email), Some(owner))
        token = p3.token.get
        // the code is consumed
        p4 <- post(app, "/api/v1/auth/device/token", DevicePoll(codes.deviceCode), anon).flatMap(
          decode[DeviceToken]
        )
        _ = assertEquals(p4.status, "expired")
        me <- get(app, "/api/v1/auth/me", bearer(token)).flatMap(decode[Me])
        _ = assertEquals(me.user.email, owner)
        r <- pushed(app, bearer(token))
        proj <- get(app, "/api/v1/workspaces/rotman/projects/sherlock", bearer(token)).flatMap(
          decode[ProjectSummary]
        )
      yield assertEquals(proj.head, Some(r.revisionId))
  }

  // ---------------------------------------------------------------- credentials

  server.test("a credential publishes to its project only; revoked credentials are 401") { app =>
    for
      c <- login(app)
      created <- post(
        app,
        "/api/v1/workspaces/rotman/projects/sherlock/credentials",
        CreateCredential("batch"),
        cookie(c)
      )
      _ = assertEquals(created.status, Status.Created)
      cred <- decode[CredentialCreated](created)
      _ = assert(cred.secret.length > 32)
      listed <- get(app, "/api/v1/workspaces/rotman/projects/sherlock/credentials", cookie(c))
        .flatMap(decode[List[CredentialSummary]])
      _ = assertEquals(listed.map(_.id), List(cred.id))
      // the credential may not even create a session on another project: authorization precedes
      // existence, so it learns nothing about project B
      other <- post(
        app,
        "/api/v1/workspaces/rotman/projects/other/upload-sessions",
        CreateUploadSession("sha256:" + "0" * 64, 1, None, Nil),
        bearer(cred.secret)
      )
      _ = assertEquals(other.status, Status.Forbidden)
      e <- decode[ApiError](other)
      _ = assertEquals(e.code, "forbidden")
      // a credential is not a user: no `me`, no credential management
      me <- get(app, "/api/v1/auth/me", bearer(cred.secret))
      _ = assertEquals(me.status, Status.Forbidden)
      mgmt <-
        get(app, "/api/v1/workspaces/rotman/projects/sherlock/credentials", bearer(cred.secret))
      _ = assertEquals(mgmt.status, Status.Forbidden)
      r <- pushed(app, bearer(cred.secret))
      readable <- get(app, s"/api/v1/revisions/${r.revisionId}", bearer(cred.secret))
      _ = assertEquals(readable.status, Status.Ok)
      revoked <- delete(
        app,
        s"/api/v1/workspaces/rotman/projects/sherlock/credentials/${cred.id}",
        cookie(c)
      )
      _ = assertEquals(revoked.status, Status.NoContent)
      after <- get(app, s"/api/v1/revisions/${r.revisionId}", bearer(cred.secret))
      _ = assertEquals(after.status, Status.Unauthorized)
      gone <- get(app, "/api/v1/workspaces/rotman/projects/sherlock/credentials", cookie(c))
        .flatMap(decode[List[CredentialSummary]])
    yield assertEquals(gone, Nil)
  }

  // ---------------------------------------------------------------- saved views

  server.test("saving and updating a view increments its version and never touches the revision") {
    app =>
      for
        c <- login(app)
        r <- pushed(app, cookie(c))
        before <- get(app, s"/api/v1/revisions/${r.revisionId}", cookie(c)).flatMap(
          decode[RevisionDetail]
        )
        state1 = Json.obj("layers" -> Json.arr(Json.fromString("speech-t")))
        state2 = Json.obj("layers" -> Json.arr(Json.fromString("speech-z")), "cursor" -> Json.Null)
        v <- post(
          app,
          s"/api/v1/revisions/${r.revisionId}/views",
          SaveView("speech map", state1),
          cookie(c)
        ).flatMap { resp =>
          assertEquals(resp.status, Status.Created)
          decode[SavedViewDetail](resp)
        }
        _ = assertEquals(v.latest, 1)
        _ = assertEquals(v.versions.map(_.state), List(state1))
        _ = assertEquals(v.revision, r.revisionId)
        v2 <- put(app, s"/api/v1/views/${v.id}", UpdateView(state2), cookie(c)).flatMap(
          decode[SavedViewDetail]
        )
        _ = assertEquals(v2.latest, 2)
        _ = assertEquals(v2.versions.map(_.version), List(1, 2))
        _ = assertEquals(v2.versions.last.state, state2)
        got <- get(app, s"/api/v1/views/${v.id}", cookie(c)).flatMap(decode[SavedViewDetail])
        _ = assertEquals(got, v2)
        list <- get(app, s"/api/v1/revisions/${r.revisionId}/views", cookie(c)).flatMap(
          decode[List[SavedViewSummary]]
        )
        _ = assertEquals(list.map(s => (s.id, s.latest)), List((v.id, 2)))
        after <- get(app, s"/api/v1/revisions/${r.revisionId}", cookie(c)).flatMap(
          decode[RevisionDetail]
        )
        _ = assertEquals(after.digest, before.digest)
        _ = assertEquals(after, before)
        proj <- get(app, "/api/v1/workspaces/rotman/projects/sherlock", cookie(c)).flatMap(
          decode[ProjectSummary]
        )
        _ = assertEquals(proj.revisions.length, 1)
        anonView <- get(app, s"/api/v1/views/${v.id}", anon)
      yield assertEquals(anonView.status, Status.Unauthorized)
  }

  // ---------------------------------------------------------------- share links

  server.test(
    "a share link opens without an account, serves renditions by secret, and dies on revoke"
  ) {
    app =>
      for
        c <- login(app)
        r <- pushed(app, cookie(c))
        v <- post(
          app,
          s"/api/v1/revisions/${r.revisionId}/views",
          SaveView("shared", Json.obj("layers" -> Json.arr())),
          cookie(c)
        ).flatMap(decode[SavedViewDetail])
        noAuth <- post(
          app,
          s"/api/v1/views/${v.id}/versions/1/links",
          CreateShareLink(Some(7)),
          anon
        )
        _ = assertEquals(noAuth.status, Status.Unauthorized)
        link <- post(
          app,
          s"/api/v1/views/${v.id}/versions/1/links",
          CreateShareLink(Some(7)),
          cookie(c)
        ).flatMap { resp =>
          assertEquals(resp.status, Status.Created)
          decode[ShareLinkCreated](resp)
        }
        _ = assertEquals(link.secret.length, 32)
        _ = assertEquals(link.url, s"http://test/s/${link.secret}")
        _ = assert(link.expiresAt.isDefined)
        missing <- post(
          app,
          s"/api/v1/views/${v.id}/versions/2/links",
          CreateShareLink(None),
          cookie(c)
        )
        _ = assertEquals(missing.status, Status.NotFound)
        opened <- get(app, s"/api/v1/share/${link.secret}", anon)
        _ = assertEquals(opened.status, Status.Ok)
        shared <- decode[SharedView](opened)
        _ = assertEquals(shared.view.id, v.id)
        _ = assertEquals(shared.version.version, 1)
        _ = assertEquals(shared.revision.id, r.revisionId)
        _ =
          assert(shared.revision.renditions.forall(_.headerUrl.contains(s"/share/${link.secret}/")))
        hdr <- get(app, s"/api/v1/share/${link.secret}/renditions/speech-t/header", anon)
        _ = assertEquals(hdr.status, Status.Ok)
        body <- hdr.as[String]
        _ = assert(body.contains("volume-f32"))
        pay <- get(app, s"/api/v1/share/${link.secret}/renditions/speech-t/payload", anon)
        _ = assertEquals(pay.status, Status.Ok)
        // the member route stays closed to anonymous callers, and a bad secret is refused
        direct <- get(app, s"/api/v1/revisions/${r.revisionId}/renditions/speech-t/header", anon)
        _ = assertEquals(direct.status, Status.Unauthorized)
        bogus <- get(app, s"/api/v1/share/${"x" * 32}", anon)
        _ = assertEquals(bogus.status, Status.Unauthorized)
        listed <- get(app, "/api/v1/workspaces/rotman/projects/sherlock/links", cookie(c)).flatMap(
          decode[List[ShareLinkSummary]]
        )
        _ = assertEquals(listed.map(l => (l.id, l.view, l.version)), List((link.id, v.id, 1)))
        revoked <- delete(app, s"/api/v1/links/${link.id}", cookie(c))
        _ = assertEquals(revoked.status, Status.NoContent)
        gone <- get(app, s"/api/v1/share/${link.secret}", anon)
        _ = assertEquals(gone.status, Status.Gone)
        e <- decode[ApiError](gone)
        _ = assertEquals(e.code, "revoked")
        goneHdr <- get(app, s"/api/v1/share/${link.secret}/renditions/speech-t/header", anon)
        _ = assertEquals(goneHdr.status, Status.Gone)
        // members are unaffected
        still <- get(app, s"/api/v1/views/${v.id}", cookie(c))
        _ = assertEquals(still.status, Status.Ok)
        audit <- get(app, "/api/v1/workspaces/rotman/audit", cookie(c)).flatMap(
          decode[List[AuditEvent]]
        )
        actions = audit.map(_.action)
        _ = assert(actions.contains("login"), actions)
        _ = assert(actions.contains("publish"), actions)
        _ = assert(actions.contains("share.create"), actions)
        _ = assert(actions.contains("share.revoke"), actions)
        _ = assertEquals(audit.find(_.action == "share.revoke").flatMap(_.subject), Some(link.id))
        anonAudit <- get(app, "/api/v1/workspaces/rotman/audit", anon)
      yield assertEquals(anonAudit.status, Status.Unauthorized)
  }

  // ---------------------------------------------------------------- provenance

  server.test(
    "provenance reports the AR facet as two groups, never shared, and unknown ops as unsupported"
  ) {
    app =>
      for
        c <- login(app)
        r <- pushed(app, cookie(c))
        anonProv <- get(app, s"/api/v1/revisions/${r.revisionId}/provenance", anon)
        _ = assertEquals(anonProv.status, Status.Unauthorized)
        prov <- get(app, s"/api/v1/revisions/${r.revisionId}/provenance", cookie(c)).flatMap(
          decode[Provenance]
        )
        _ = assertEquals(prov.revision, r.revisionId)
        _ = assertEquals(prov.receiptSchema, Some("org.bbuchsbaum.fmrireg/analysis-receipt"))
        _ = assertEquals(prov.receiptCount, 2)
        ar = prov.facets.find(_.facet == "temporalNoise").get
        _ = assertEquals(ar.shared, false)
        _ = assertEquals(
          ar.groups.map(g => (g.value, g.count, g.members)).toSet,
          Set(
            (Json.fromString("AR(2)"), 1, List("first-level-01")),
            (Json.fromString("AR(1)"), 1, List("first-level-02"))
          )
        )
        drift = prov.facets.find(_.facet == "drift").get
        _ = assertEquals(drift.shared, true)
        _ = assertEquals(drift.groups.map(_.count), List(2))
        _ = assert(!prov.facets.exists(_.facet == "subject"))
        byId = prov.nodes.map(n => n.id -> n).toMap
        _ = assertEquals(byId("smoothing").interpretation, "unsupported")
        _ = assertEquals(byId("smoothing").schemaId, Some("org.example.lab/smooth"))
        _ = assertEquals(
          byId("smoothing").payload.hcursor.downField("payload").get[Int]("fwhmMm"),
          Right(6)
        )
        _ = assertEquals(byId("first-level-01").interpretation, "understood")
        _ = assertEquals(byId("group-reduce").interpretation, "understood")
        _ = assertEquals(byId("raw-bids").kind, "entity")
        _ = assertEquals(byId("raw-bids").hosted, Some(false))
        _ = assertEquals(byId("speech-t").kind, "asset")
        _ = assertEquals(prov.edges.length, 6)
        again <- get(app, s"/api/v1/revisions/${r.revisionId}/provenance", cookie(c)).flatMap(
          decode[Provenance]
        )
      yield assertEquals(again, prov)
  }

  // ---------------------------------------------------------------- private by default

  server.test("the bootstrap project is private: anonymous reads are 401, members read") { app =>
    for
      p <- get(app, "/api/v1/workspaces/rotman/projects/sherlock", anon)
      _ = assertEquals(p.status, Status.Unauthorized)
      e <- decode[ApiError](p)
      _ = assertEquals(e.code, "unauthorized")
      junk <- get(app, "/api/v1/workspaces/rotman/projects/sherlock", bearer("not-a-token"))
      _ = assertEquals(junk.status, Status.Unauthorized)
      c <- login(app)
      ok <- get(app, "/api/v1/workspaces/rotman/projects/sherlock", cookie(c))
    yield assertEquals(ok.status, Status.Ok)
  }

  test("OpenAPI document lists the Stage 4 endpoints") {
    assert(Routes.openApiYaml.contains("/auth/device/token"))
    assert(Routes.openApiYaml.contains("/share/{secret}"))
    assert(Routes.openApiYaml.contains("/revisions/{revision}/provenance"))
  }
