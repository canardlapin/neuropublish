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
  * audit — against the routes in-process, with no static token configured. Parameterized over the
  * record stores: [[Stage4Suite]] runs it on the local fs, `PgStage4Suite` on PostgreSQL.
  */
abstract class Stage4Spec(factory: ServerFactory) extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 2.minutes
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val owner = "owner@example.org"
  private val password = "owner-dev-password"

  private def server = ResourceFunFixture(
    factory.build(key, "http://test", owner, password, legacyToken = None).map(_.app)
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
    bytes("reference/manifest.json").flatMap(m => pushBytes(app, auth, m, key, parent))
  private def pushWith(app: HttpApp[IO], auth: Auth, manifest: Array[Byte]): IO[CommitResult] =
    pushBytes(app, auth, manifest, key, None).flatMap { c =>
      assertEquals(c.status, Status.Created)
      decode[CommitResult](c)
    }
  private def pushedTo(app: HttpApp[IO], auth: Auth, to: ProjectKey): IO[CommitResult] =
    bytes("reference/manifest.json").flatMap(pushBytes(app, auth, _, to, None)).flatMap { c =>
      assertEquals(c.status, Status.Created)
      decode[CommitResult](c)
    }
  private def pushBytes(
      app: HttpApp[IO],
      auth: Auth,
      manifest: Array[Byte],
      to: ProjectKey,
      parent: Option[String]
  ) =
    for
      assets <- List("t1", "speech-effect", "speech-se", "speech-t", "speech-z").traverse(id =>
        bytes(s"reference/assets/$id.nii").map(id -> _)
      )
      inv = assets.map((_, b) =>
        AssetInventory(Sha256.of(b).render, b.length.toLong, "application/x-nifti")
      )
      created <- post(
        app,
        s"/api/v1/workspaces/${to.workspace}/projects/${to.project}/upload-sessions",
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

  private def serverWithStores = ResourceFunFixture(
    factory.build(key, "http://test", owner, password, legacyToken = None)
  )

  serverWithStores.test(
    "stored secrets are hashes: no clear password, session, token, or link secret"
  ) {
    srv =>
      val app = srv.app
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
        all <- srv.storedText
        _ = assert(!all.contains(password), "clear password stored")
        _ = assert(!all.contains(c), "session secret stored")
        _ = assert(!all.contains(tok.token.get), "user token stored")
        _ = assert(!all.contains(cred.secret), "credential secret stored")
        _ = assert(!all.contains(link.secret), "link secret stored")
      yield assert(all.contains("pbkdf2-hmac-sha256"), "no PBKDF2 password hash stored")
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
        // polling faster than the interval is answered "slow_down" (RFC 8628)
        p2 <- post(app, "/api/v1/auth/device/token", DevicePoll(codes.deviceCode), anon).flatMap(
          decode[DeviceToken]
        )
        _ = assertEquals(p2.status, "slow_down")
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

  server.test("device flow: a session can deny the code; the CLI's next poll is `denied`") {
    app =>
      for
        codes <- post(app, "/api/v1/auth/device", DeviceStart("npub"), anon).flatMap(
          decode[DeviceCodes]
        )
        c <- login(app)
        denied <- post(app, "/api/v1/auth/device/deny", DeviceApprove(codes.userCode), cookie(c))
        _ = assertEquals(denied.status, Status.NoContent)
        p <- post(app, "/api/v1/auth/device/token", DevicePoll(codes.deviceCode), anon).flatMap(
          decode[DeviceToken]
        )
        _ = assertEquals(p.status, "denied")
        // the code is consumed: it cannot be approved afterwards
        late <- post(app, "/api/v1/auth/device/approve", DeviceApprove(codes.userCode), cookie(c))
      yield assertEquals(late.status, Status.BadRequest)
  }

  /** Mint a user token through the device flow (sleeping past the poll interval). */
  private def userToken(app: HttpApp[IO], c: String): IO[String] =
    for
      codes <- post(app, "/api/v1/auth/device", DeviceStart("npub"), anon).flatMap(
        decode[DeviceCodes]
      )
      _ <- post(app, "/api/v1/auth/device/approve", DeviceApprove(codes.userCode), cookie(c))
      t <- post(app, "/api/v1/auth/device/token", DevicePoll(codes.deviceCode), anon).flatMap(
        decode[DeviceToken]
      )
    yield t.token.get

  serverWithStores.test(
    "user tokens expire after 30 days and are revoked by logout or all at once"
  ) {
    srv =>
      val app = srv.app
      for
        c <- login(app)
        t1 <- userToken(app, c)
        t2 <- userToken(app, c)
        ok <- get(app, "/api/v1/auth/me", bearer(t1))
        _ = assertEquals(ok.status, Status.Ok)
        // the stored record carries an expiry 30 days out
        exp <- srv.tokenExpiry(t1).map(_.get)
        now <- IO.realTimeInstant
        _ = assert(exp.isAfter(now.plusSeconds(29 * 86400)) &&
          exp.isBefore(now.plusSeconds(31 * 86400)))
        // an expired token is a generic 401
        _ <- srv.setTokenExpiry(t1, now.minusSeconds(1))
        expired <- get(app, "/api/v1/auth/me", bearer(t1))
        _ = assertEquals(expired.status, Status.Unauthorized)
        e <- decode[ApiError](expired)
        _ = assertEquals(e.message, "invalid token")
        // logout on a bearer revokes that token only
        out <- post(app, "/api/v1/auth/logout", Json.obj(), bearer(t2))
        _ = assertEquals(out.status, Status.NoContent)
        revoked <- get(app, "/api/v1/auth/me", bearer(t2))
        _ = assertEquals(revoked.status, Status.Unauthorized)
        sessionStill <- get(app, "/api/v1/auth/me", cookie(c))
        _ = assertEquals(sessionStill.status, Status.Ok)
        // DELETE auth/tokens revokes every token of the caller
        t3 <- userToken(app, c)
        t4 <- userToken(app, c)
        all <- delete(app, "/api/v1/auth/tokens", cookie(c))
        _ = assertEquals(all.status, Status.NoContent)
        r3 <- get(app, "/api/v1/auth/me", bearer(t3))
        r4 <- get(app, "/api/v1/auth/me", bearer(t4))
        _ = assertEquals((r3.status, r4.status), (Status.Unauthorized, Status.Unauthorized))
        anonAll <- delete(app, "/api/v1/auth/tokens", anon)
      yield assertEquals(anonAll.status, Status.Unauthorized)
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

  server.test("view state is capped at 256 KB per version and 200 versions per view") { app =>
    for
      c <- login(app)
      r <- pushed(app, cookie(c))
      big = Json.obj("blob" -> Json.fromString("x" * (256 * 1024 + 1)))
      tooBig <-
        post(app, s"/api/v1/revisions/${r.revisionId}/views", SaveView("big", big), cookie(c))
      _ = assertEquals(tooBig.status, Status.BadRequest)
      v <-
        post(app, s"/api/v1/revisions/${r.revisionId}/views", SaveView("v", Json.obj()), cookie(c))
          .flatMap(decode[SavedViewDetail])
      bigUpdate <- put(app, s"/api/v1/views/${v.id}", UpdateView(big), cookie(c))
      _ = assertEquals(bigUpdate.status, Status.BadRequest)
      _ <- (2 to 200).toList.traverse_(i =>
        put(app, s"/api/v1/views/${v.id}", UpdateView(Json.obj("i" -> Json.fromInt(i))), cookie(c))
          .map(resp => assertEquals(resp.status, Status.Ok, s"version $i"))
      )
      full <- get(app, s"/api/v1/views/${v.id}", cookie(c)).flatMap(decode[SavedViewDetail])
      _ = assertEquals(full.latest, 200)
      over <- put(app, s"/api/v1/views/${v.id}", UpdateView(Json.obj()), cookie(c))
      _ = assertEquals(over.status, Status.BadRequest)
      still <- get(app, s"/api/v1/views/${v.id}", cookie(c)).flatMap(decode[SavedViewDetail])
    yield assertEquals(still.latest, 200)
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
        body <- opened.as[String]
        shared <- IO.fromEither(io.circe.parser.decode[SharedView](body))
        _ = assertEquals(shared.view.id, v.id)
        _ = assertEquals(shared.view.project, "sherlock")
        _ = assertEquals(shared.version.version, 1)
        _ = assertEquals(shared.revision.id, r.revisionId)
        _ = assertEquals(shared.revision.message, None)
        _ = assert(
          shared.revision.committedAt.matches("\\d{4}-\\d{2}-\\d{2}"),
          shared.revision.committedAt
        )
        _ =
          assert(shared.revision.renditions.forall(_.headerUrl.contains(s"/share/${link.secret}/")))
        // the presentation subset: no provenance, subjects, method payloads, owner or saver ids
        me <- get(app, "/api/v1/auth/me", cookie(c)).flatMap(decode[Me])
        _ = List(
          "provenance",
          "\"subject\"",
          "temporalNoise",
          "\"method\"",
          "\"owner\"",
          "\"savedBy\"",
          me.user.id,
          "\"sensitivity\""
        ).foreach(needle =>
          assert(!body.contains(needle), s"share response leaks '$needle'")
        )
        _ = assert(body.contains("\"title\""))
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

  server.test("a link can only be minted for a group-level revision") { app =>
    for
      c <- login(app)
      manifest <- bytes("reference/manifest.json")
      subjectLevel = new String(manifest, "UTF-8").replace(
        "\"sensitivity\": \"group-level\"",
        "\"sensitivity\": \"subject-level\""
      ).getBytes("UTF-8")
      _ = assert(!java.util.Arrays.equals(subjectLevel, manifest))
      r <- pushWith(app, cookie(c), subjectLevel)
      v <-
        post(app, s"/api/v1/revisions/${r.revisionId}/views", SaveView("v", Json.obj()), cookie(c))
          .flatMap(decode[SavedViewDetail])
      refused <-
        post(app, s"/api/v1/views/${v.id}/versions/1/links", CreateShareLink(None), cookie(c))
      _ = assertEquals(refused.status, Status.BadRequest)
      e <- decode[ApiError](refused)
      _ = assertEquals(e.code, "bad_request")
      links <- get(app, "/api/v1/workspaces/rotman/projects/sherlock/links", cookie(c))
        .flatMap(decode[List[ShareLinkSummary]])
    yield assertEquals(links, Nil)
  }

  // ---------------------------------------------------------------- workspaces are islands

  private val keyB = ProjectKey("labb", "projb")
  private val ownerB = "owner-b@example.org"
  private val passwordB = "owner-b-password"
  private def twoWorkspaces = ResourceFunFixture(
    factory.build(
      key,
      "http://test",
      owner,
      password,
      legacyToken = None,
      extra = List(Server.Bootstrap(keyB, ownerB, passwordB))
    ).map(_.app)
  )

  twoWorkspaces.test(
    "a member of A learns nothing about B; a viewer of A reads but cannot save or share"
  ) { app =>
    for
      a <- login(app)
      b <- login(app, ownerB, passwordB)
      meB <- get(app, "/api/v1/auth/me", cookie(b)).flatMap(decode[Me])
      _ = assertEquals(meB.memberships, List(Membership("labb", "owner")))
      rA <- pushed(app, cookie(a))
      rB <- pushedTo(app, cookie(b), keyB)
      vA <-
        post(app, s"/api/v1/revisions/${rA.revisionId}/views", SaveView("a", Json.obj()), cookie(a))
          .flatMap(decode[SavedViewDetail])
      vB <-
        post(app, s"/api/v1/revisions/${rB.revisionId}/views", SaveView("b", Json.obj()), cookie(b))
          .flatMap(decode[SavedViewDetail])
      lA <- post(app, s"/api/v1/views/${vA.id}/versions/1/links", CreateShareLink(None), cookie(a))
        .flatMap(decode[ShareLinkCreated])
      lB <- post(app, s"/api/v1/views/${vB.id}/versions/1/links", CreateShareLink(None), cookie(b))
        .flatMap(decode[ShareLinkCreated])
      credA <- post(
        app,
        "/api/v1/workspaces/rotman/projects/sherlock/credentials",
        CreateCredential("a-batch"),
        cookie(a)
      ).flatMap(decode[CredentialCreated])
      // A on B's records: the same 404 as for records that do not exist
      r1 <- get(app, s"/api/v1/revisions/${rB.revisionId}", cookie(a))
      r2 <- get(app, s"/api/v1/revisions/${rB.revisionId}/provenance", cookie(a))
      r3 <- get(app, s"/api/v1/revisions/${rB.revisionId}/renditions/speech-t/header", cookie(a))
      r4 <- get(app, s"/api/v1/views/${vB.id}", cookie(a))
      r5 <- put(app, s"/api/v1/views/${vB.id}", UpdateView(Json.obj()), cookie(a))
      r6 <- delete(app, s"/api/v1/links/${lB.id}", cookie(a))
      r7 <- post(app, s"/api/v1/views/${vB.id}/versions/1/links", CreateShareLink(None), cookie(a))
      r8 <- get(app, s"/api/v1/revisions/${rB.revisionId}/views", cookie(a))
      r9 <- delete(app, s"/api/v1/workspaces/labb/projects/projb/credentials/c-nope", cookie(a))
      _ = assertEquals(
        List(r1, r2, r3, r4, r5, r6, r7, r8, r9).map(_.status),
        List.fill(9)(Status.NotFound)
      )
      // B's link is still alive: A's 404s did nothing
      openB <- get(app, s"/api/v1/share/${lB.secret}", anon)
      _ = assertEquals(openB.status, Status.Ok)
      // A's credential on B's project: a scoping error for a legitimate principal → 403
      cross <- post(
        app,
        "/api/v1/workspaces/labb/projects/projb/upload-sessions",
        CreateUploadSession("sha256:" + "0" * 64, 1, None, Nil),
        bearer(credA.secret)
      )
      _ = assertEquals(cross.status, Status.Forbidden)
      crossRead <- get(app, "/api/v1/workspaces/labb/projects/projb", bearer(credA.secret))
      _ = assertEquals(crossRead.status, Status.Forbidden)
      // A's share link serves A's revision only
      sharedA <- get(app, s"/api/v1/share/${lA.secret}", anon).flatMap(decode[SharedView])
      _ = assertEquals(sharedA.revision.id, rA.revisionId)
      _ = assert(sharedA.revision.renditions.forall(_.headerUrl.contains(s"/share/${lA.secret}/")))
      hdrA <- get(app, s"/api/v1/share/${lA.secret}/renditions/speech-t/header", anon)
      _ = assertEquals(hdrA.status, Status.Ok)
      noSuch <- get(app, s"/api/v1/share/${lA.secret}/renditions/not-in-a/header", anon)
      _ = assertEquals(noSuch.status, Status.NotFound)
      // members API: A's owner adds a viewer (new user, one-time password) and attaches B's owner
      viewerAdded <- post(
        app,
        "/api/v1/workspaces/rotman/members",
        AddMember("viewer@example.org", "viewer"),
        cookie(a)
      )
      _ = assertEquals(viewerAdded.status, Status.Created)
      viewer <- decode[MemberAdded](viewerAdded)
      _ = assertEquals(viewer.role, "viewer")
      _ = assert(viewer.oneTimePassword.exists(_.length >= 16))
      attached <-
        post(app, "/api/v1/workspaces/rotman/members", AddMember(ownerB, "member"), cookie(a))
          .flatMap(decode[MemberAdded])
      _ = assertEquals((attached.user.email, attached.oneTimePassword), (ownerB, None))
      members <- get(app, "/api/v1/workspaces/rotman/members", cookie(a))
        .flatMap(decode[List[MemberSummary]])
      _ = assertEquals(
        members.map(m => (m.user.email, m.role)).toSet,
        Set((owner, "owner"), ("viewer@example.org", "viewer"), (ownerB, "member"))
      )
      byB <- get(app, "/api/v1/workspaces/labb/members", cookie(a))
      _ = assertEquals(byB.status, Status.Forbidden)
      badRole <-
        post(app, "/api/v1/workspaces/rotman/members", AddMember("x@example.org", "god"), cookie(a))
      _ = assertEquals(badRole.status, Status.BadRequest)
      // the viewer reads A but cannot save a view or mint a link
      vc <- login(app, "viewer@example.org", viewer.oneTimePassword.get)
      canRead <- get(app, s"/api/v1/revisions/${rA.revisionId}", cookie(vc))
      _ = assertEquals(canRead.status, Status.Ok)
      canReadView <- get(app, s"/api/v1/views/${vA.id}", cookie(vc))
      _ = assertEquals(canReadView.status, Status.Ok)
      noSave <- post(
        app,
        s"/api/v1/revisions/${rA.revisionId}/views",
        SaveView("x", Json.obj()),
        cookie(vc)
      )
      _ = assertEquals(noSave.status, Status.Forbidden)
      noUpdate <- put(app, s"/api/v1/views/${vA.id}", UpdateView(Json.obj()), cookie(vc))
      _ = assertEquals(noUpdate.status, Status.Forbidden)
      noLink <-
        post(app, s"/api/v1/views/${vA.id}/versions/1/links", CreateShareLink(None), cookie(vc))
      _ = assertEquals(noLink.status, Status.Forbidden)
      noPublish <- post(
        app,
        "/api/v1/workspaces/rotman/projects/sherlock/upload-sessions",
        CreateUploadSession("sha256:" + "0" * 64, 1, None, Nil),
        cookie(vc)
      )
      _ = assertEquals(noPublish.status, Status.Forbidden)
      // B's owner, now a plain member of A, may read A but not its audit
      bReadsA <- get(app, s"/api/v1/revisions/${rA.revisionId}", cookie(b))
      _ = assertEquals(bReadsA.status, Status.Ok)
      bAuditA <- get(app, "/api/v1/workspaces/rotman/audit", cookie(b))
      _ = assertEquals(bAuditA.status, Status.Forbidden)
      // A's audit holds none of B's events
      auditA <-
        get(app, "/api/v1/workspaces/rotman/audit", cookie(a)).flatMap(decode[List[AuditEvent]])
      _ = assert(auditA.nonEmpty)
      _ = assert(auditA.forall(_.workspace == "rotman"))
      _ = assert(
        !auditA.exists(e =>
          e.subject.contains(rB.revisionId) || e.subject.contains(vB.id) ||
            e.subject.contains(lB.id)
        ),
        auditA
      )
      _ = assert(auditA.exists(_.action == "member.add"))
      auditB <-
        get(app, "/api/v1/workspaces/labb/audit", cookie(b)).flatMap(decode[List[AuditEvent]])
      _ = assert(auditB.exists(_.action == "share.create"))
    yield assert(auditB.forall(_.workspace == "labb"))
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

class Stage4Suite extends Stage4Spec(ServerFactory.Local)
