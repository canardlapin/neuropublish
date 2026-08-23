package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import java.time.Instant
import neuropublish.api.*
import neuropublish.protocol.ProtocolVersion
import org.http4s.HttpRoutes
import sttp.apispec.openapi.circe.yaml.*
import sttp.model.headers.{Cookie, CookieValueWithMeta}
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.http4s.Http4sServerInterpreter

/** Every endpoint resolves a [[Principal]] first (cookie or bearer) and then applies the rules in
  * [[Authz]]. Share routes are the one exception: they authorize by link secret.
  */
final class Routes(
    pub: Publication,
    revisions: RevisionStore,
    ingestion: Ingestion,
    identity: Identity,
    members: Members,
    sessions: Sessions,
    userTokens: UserTokens,
    device: DeviceFlow,
    credentials: Credentials,
    views: Views,
    links: ShareLinks,
    audit: Audit,
    authz: Authz,
    data: Path,
    baseUrl: String
):
  private type Res[A] = IO[Either[ApiError, A]]
  private def notFound(what: String): ApiError = ApiError("not_found", what)
  private def badRequest(msg: String): ApiError = ApiError("bad_request", msg)
  private val secure = baseUrl.startsWith("https://")

  /** Caps on the opaque saved-view state: one version's JSON and the versions per view. */
  private val MaxStateBytes = 256 * 1024
  private val MaxVersions = 200

  private def sessionCookie(secret: String, expires: Instant): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      secret,
      expires = Some(expires),
      maxAge = Some(sessions.lifetime.toSeconds),
      domain = None,
      path = Some("/"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(Cookie.SameSite.Lax)
    )
  private val clearedCookie: CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      "",
      expires = Some(Instant.EPOCH),
      maxAge = Some(0L),
      domain = None,
      path = Some("/"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(Cookie.SameSite.Lax)
    )

  private val resolve: ((Option[String], Option[String])) => Res[Principal] =
    (bearer, cookie) => authz.resolve(bearer, cookie)

  private def me(u: UserRecord): IO[Me] = members.membershipsOf(u.id).map(Me(u.public, _))

  // ------------------------------------------------------------------ helpers

  private def withE[A, B](r: Res[A])(f: A => Res[B]): Res[B] =
    r.flatMap {
      case Left(e) => IO.pure(Left(e))
      case Right(a) => f(a)
    }

  /** Anonymous callers are refused before any lookup, so 404 never leaks existence. */
  private def authed[A](p: Principal)(body: => Res[A]): Res[A] =
    if p == Principal.Anonymous then IO.pure(Left(ApiError("unauthorized", "sign in required")))
    else body

  /** A record addressed by id that only its project's readers may learn about: a missing record and
    * a record the principal may not read are the same 404 (no existence oracle).
    */
  private def scoped[A](p: Principal, what: String, lookup: IO[Option[A]])(key: A => ProjectKey)
      : Res[A] =
    authed(p):
      lookup.flatMap {
        case None => IO.pure(Left(notFound(what)))
        case Some(a) =>
          authz.canRead(p, key(a)).map {
            case Right(_) => Right(a)
            case Left(e) if e.code == "forbidden" => Left(notFound(what))
            case Left(e) => Left(e)
          }
      }
  private def revisionOf(p: Principal, id: String): Res[RevisionRecord] =
    scoped(
      p,
      s"revision $id does not exist",
      if !Ids.valid(id) then IO.none else revisions.revision(id)
    )(r => ProjectKey(r.workspace, r.project))
  private def viewOf(p: Principal, id: String): Res[ViewRecord] =
    scoped(p, s"view $id does not exist", views.get(id))(v => ProjectKey(v.workspace, v.project))
  private def linkOf(p: Principal, id: String): Res[ShareLinkRecord] =
    scoped(p, s"link $id does not exist", links.get(id))(l => ProjectKey(l.workspace, l.project))
  private def projectOf(p: Principal, ws: String, proj: String): Res[ProjectKey] = authed(p):
    val key = ProjectKey(ws, proj)
    if !Ids.valid(ws) || !Ids.valid(proj) then
      IO.pure(Left(notFound(s"project $ws/$proj does not exist")))
    else
      revisions.projectExists(key).map(if _ then Right(key)
      else Left(notFound(s"project ${key.render} does not exist")))
  private def uploadKey(p: Principal, session: String): Res[ProjectKey] = authed(p):
    pub.sessionKey(session).map(_.toRight(notFound(s"upload session $session does not exist")))

  private def readIf(rev: String, asset: String, path: (String, String) => Path): Res[Array[Byte]] =
    if !Ids.valid(rev) || !Ids.valid(asset) then IO.pure(Left(notFound("no such rendition")))
    else
      Files[IO].exists(path(rev, asset)).flatMap {
        case false => IO.pure(Left(notFound("rendition not ready")))
        case true => Files[IO].readAll(path(rev, asset)).compile.to(Array).map(Right(_))
      }
  private def header(rev: String, asset: String): Res[String] =
    readIf(rev, asset, ingestion.headerPath).map(_.map(b => new String(b, "UTF-8")))

  /** Audit rows are per workspace; a user-level event lands in every workspace the user belongs to.
    */
  private def auditUser(u: UserRecord, action: String, detail: Option[String]): IO[Unit] =
    members.membershipsOf(u.id).flatMap(_.traverse_(m =>
      audit.record(s"user:${u.id}", action, m.workspace, None, Some(u.email), detail)
    ))

  // ------------------------------------------------------------------ stage 1 (publication)

  private val health = Endpoints.health.serverLogicSuccess(_ =>
    IO.pure(Health("ok", ProtocolVersion.current.render, "neuropublish-backend"))
  )

  private val create =
    Protocol.createUploadSession.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (ws, proj, req) =>
        val key = ProjectKey(ws, proj)
        withE(authz.canPublish(p, key))(_ => pub.createSession(key, req))
    )
  private val putObject =
    Protocol.uploadObject.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (s, d, b) =>
        withE(uploadKey(p, s))(key =>
          withE(authz.canPublish(p, key))(_ => pub.uploadObject(s, d, b))
        )
    )
  private val putManifest =
    Protocol.uploadManifest.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (s, b) =>
        withE(uploadKey(p, s))(key =>
          withE(authz.canPublish(p, key))(_ => pub.uploadManifest(s, b))
        )
    )
  private val commit = Protocol.commit.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
    (s, req) =>
      withE(uploadKey(p, s)) { key =>
        withE(authz.canPublish(p, key)) { _ =>
          pub.commit(s, req).flatTap {
            case Right(r) =>
              audit.record(
                p.actor,
                "publish",
                key.workspace,
                Some(key.project),
                Some(r.revisionId),
                req.message
              )
            case Left(_) => IO.unit
          }
        }
      }
  )
  private val project =
    Protocol.project.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (ws, proj) =>
        val key = ProjectKey(ws, proj)
        withE(authz.canRead(p, key))(_ => pub.project(key))
    )
  private val revision = Protocol.revision.serverSecurityLogic[Principal, IO](resolve).serverLogic(
    p =>
      id =>
        withE(revisionOf(p, id))(r =>
          withE(authz.canRead(p, ProjectKey(r.workspace, r.project)))(_ => pub.revision(id))
        )
  )
  private def rendition[A](p: Principal, rev: String, f: => Res[A]): Res[A] =
    withE(revisionOf(p, rev))(r =>
      withE(authz.canRead(p, ProjectKey(r.workspace, r.project)))(_ => f)
    )
  private val renditionHeader =
    Protocol.renditionHeader.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (r, a) => rendition(p, r, header(r, a))
    )
  private val renditionPayload =
    Protocol.renditionPayload.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (r, a) => rendition(p, r, readIf(r, a, ingestion.payloadPath))
    )

  // ------------------------------------------------------------------ identity

  private val login = Stage4.login.serverLogic[IO] { req =>
    identity.authenticate(req.email, req.password).flatMap {
      case None => IO.pure(Left(ApiError("unauthorized", "invalid email or password")))
      case Some(u) =>
        for
          (secret, exp) <- sessions.create(u.id)
          m <- me(u)
          _ <- auditUser(u, "login", None)
        yield Right((sessionCookie(secret, exp), m))
    }
  }
  private val logout = Stage4.logout.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
    _ =>
      (p match
        case Principal.Session(_, secret) => sessions.revoke(secret)
        case Principal.UserToken(u, secret) =>
          userTokens.revoke(secret) *> auditUser(u, "token.revoke", Some("logout"))
        case _ => IO.unit
      ).as(Right(clearedCookie))
  )
  private val revokeTokens =
    Stage4.revokeTokens.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      _ =>
        withE(IO.pure(authz.requireUser(p)))(u =>
          userTokens.revokeAll(u.id).flatMap(n =>
            auditUser(u, "token.revoke", Some(s"all ($n)")).as(Right(()))
          )
        )
    )
  private val whoami = Stage4.me.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
    _ => authz.requireUser(p).traverse(me)
  )

  private val deviceStart = Stage4.deviceStart.serverLogic[IO] { req =>
    device.start(req.client).map(g =>
      Right(DeviceCodes(
        g.deviceCode,
        g.userCode,
        s"$baseUrl/device",
        s"$baseUrl/device?code=${g.userCode}",
        device.expiresIn.toSeconds.toInt,
        device.interval.toSeconds.toInt
      ))
    )
  }
  private val devicePoll = Stage4.devicePoll.serverLogic[IO] { req =>
    device.poll(req.deviceCode).flatMap {
      case device.Poll.Pending => IO.pure(Right(DeviceToken("pending", None, None, None)))
      case device.Poll.SlowDown => IO.pure(Right(DeviceToken("slow_down", None, None, None)))
      case device.Poll.Denied => IO.pure(Right(DeviceToken("denied", None, None, None)))
      case device.Poll.Expired => IO.pure(Right(DeviceToken("expired", None, None, None)))
      case device.Poll.Granted(token, uid) =>
        identity.lookup(uid).map(u =>
          Right(DeviceToken("granted", Some(token), Some("user"), u.map(_.public)))
        )
    }
  }

  /** Only a browser session may approve or deny: a user token approving another would be
    * self-escalation.
    */
  private def settleDevice(p: Principal, action: String)(
      f: UserRecord => IO[Either[String, DeviceGrant]]
  ): Res[Unit] =
    p match
      case Principal.Session(u, _) =>
        f(u).flatMap {
          case Left(m) => IO.pure(Left(badRequest(m)))
          case Right(g) => auditUser(u, action, Some(g.client)).as(Right(()))
        }
      case Principal.Anonymous => IO.pure(Left(ApiError("unauthorized", "sign in required")))
      case _ => IO.pure(Left(ApiError("forbidden", "a browser session must decide the code")))
  private val deviceApprove =
    Stage4.deviceApprove.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      req => settleDevice(p, "device.approve")(u => device.approve(req.userCode, u.id))
    )
  private val deviceDeny =
    Stage4.deviceDeny.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      req => settleDevice(p, "device.deny")(_ => device.deny(req.userCode))
    )

  // ------------------------------------------------------------------ members

  private val addMember =
    Stage4.addMember.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (ws, req) =>
        withE(authz.requireAdmin(p, ws).flatMap(_.traverse(u => authz.role(p, ws).map(u -> _)))) {
          (_, callerRole) =>
            val email = req.email.trim.toLowerCase
            Role.parse(req.role) match
              case None => IO.pure(Left(badRequest("role must be owner, admin, member, or viewer")))
              case Some(_) if email.isEmpty || !email.contains('@') =>
                IO.pure(Left(badRequest("a valid email is required")))
              case Some(role) if role == Role.Owner && !callerRole.contains(Role.Owner) =>
                IO.pure(Left(ApiError("forbidden", "only an owner may add another owner")))
              case Some(role) =>
                identity.lookupIdentity(Identity.LocalIssuer, email).flatMap {
                  case Some(u) => IO.pure((u, None))
                  case None =>
                    Secrets.token(18).flatMap(pw =>
                      identity.ensureLocalUser(email, email.takeWhile(_ != '@'), pw).map(_ ->
                        Some(pw))
                    )
                }.flatMap { (u, oneTime) =>
                  members.set(ws, u.id, role) *>
                    audit.record(p.actor, "member.add", ws, None, Some(u.id), Some(role.render))
                      .as(Right(MemberAdded(u.public, role.render, oneTime)))
                }
        }
    )
  private val listMembers =
    Stage4.listMembers.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      ws =>
        withE(authz.requireAdmin(p, ws))(_ =>
          members.members(ws).flatMap(_.traverse(m =>
            identity.lookup(m.userId).map(_.map(u =>
              MemberSummary(u.public, m.role.render, m.addedAt)
            ))
          )).map(ms => Right(ms.flatten))
        )
    )

  // ------------------------------------------------------------------ credentials

  private val createCredential =
    Stage4.createCredential.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (ws, proj, req) =>
        withE(authz.requireAdmin(p, ws)) { u =>
          withE(projectOf(p, ws, proj)) { key =>
            if req.name.isBlank then IO.pure(Left(badRequest("name is required")))
            else
              credentials.create(key, req.name.trim, u.id).flatMap { (c, secret) =>
                audit.record(p.actor, "credential.create", ws, Some(proj), Some(c.id), Some(c.name))
                  .as(Right(CredentialCreated(c.id, c.name, c.project, secret)))
              }
          }
        }
    )
  private val listCredentials =
    Stage4.listCredentials.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (ws, proj) =>
        withE(authz.requireAdmin(p, ws))(_ =>
          withE(projectOf(p, ws, proj))(key =>
            credentials.list(key).map(cs => Right(cs.map(_.summary)))
          )
        )
    )
  private val revokeCredential =
    Stage4.revokeCredential.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (ws, proj, id) =>
        // a non-member learns nothing: the same 404 as for an unknown credential
        val admin = authz.requireMember(p, ws).map {
          case Left(e) if e.code == "forbidden" =>
            Left(notFound(s"credential $id does not exist in $ws/$proj"))
          case Left(e) => Left(e)
          case Right((u, r)) =>
            if r.isAdmin then Right(u)
            else Left(ApiError("forbidden", "owner or admin role required"))
        }
        withE(admin) { _ =>
          credentials.get(id).flatMap {
            case Some(c) if c.key == ProjectKey(ws, proj) =>
              credentials.revoke(id) *>
                audit.record(
                  p.actor,
                  "credential.revoke",
                  ws,
                  Some(proj),
                  Some(id),
                  Some(c.name)
                ).as(Right(()))
            case _ => IO.pure(Left(notFound(s"credential $id does not exist in $ws/$proj")))
          }
        }
    )

  // ------------------------------------------------------------------ saved views

  private def stateWithinCap(state: Json): Either[ApiError, Unit] =
    val n = state.noSpaces.getBytes("UTF-8").length
    if n > MaxStateBytes then
      Left(badRequest(s"view state is $n bytes; the limit is $MaxStateBytes"))
    else Right(())

  private val saveView = Stage4.saveView.serverSecurityLogic[Principal, IO](resolve).serverLogic(
    p =>
      (rev, req) =>
        withE(revisionOf(p, rev)) { r =>
          withE(authz.requireSharer(p, r.workspace)) { (u, _) =>
            if req.name.isBlank then IO.pure(Left(badRequest("name is required")))
            else
              withE(IO.pure(stateWithinCap(req.state)))(_ =>
                views.create(r, req.name.trim, req.state, u.id).map(v => Right(v.detail))
              )
          }
        }
  )
  private val updateView =
    Stage4.updateView.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (id, req) =>
        withE(viewOf(p, id)) { v =>
          withE(authz.requireSharer(p, v.workspace)) { (u, role) =>
            if v.owner != u.id && !role.isAdmin then
              IO.pure(Left(ApiError(
                "forbidden",
                "only the view's owner or a workspace admin may update it"
              )))
            else if v.versions.length >= MaxVersions then
              IO.pure(
                Left(badRequest(s"view $id already has $MaxVersions versions; save a new view"))
              )
            else
              withE(IO.pure(stateWithinCap(req.state)))(_ =>
                views.update(
                  id,
                  req.state,
                  u.id
                ).map(_.map(_.detail).toRight(notFound(s"view $id does not exist")))
              )
          }
        }
    )
  private val getView = Stage4.getView.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
    id => withE(viewOf(p, id))(v => IO.pure(Right(v.detail)))
  )
  private val listViews = Stage4.listViews.serverSecurityLogic[Principal, IO](resolve).serverLogic(
    p =>
      rev =>
        withE(revisionOf(p, rev))(_ =>
          views.listForRevision(rev).map(vs => Right(vs.map(_.summary)))
        )
  )

  // ------------------------------------------------------------------ share links

  private val createShareLink =
    Stage4.createShareLink.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (id, version, req) =>
        withE(viewOf(p, id)) { v =>
          withE(authz.requireSharer(p, v.workspace)) { (u, _) =>
            if v.version(version).isEmpty then
              IO.pure(Left(notFound(s"view $id has no version $version")))
            else if req.expiresInDays.exists(_ <= 0) then
              IO.pure(Left(badRequest("expiresInDays must be positive")))
            else
              // publication policy is checked where the link is minted, not where it is opened
              val policy = pub.revision(v.revision).map(_.flatMap(rd =>
                SharedProjection.shareable(rd.manifest).leftMap(badRequest)
              ))
              withE(policy)(_ =>
                links.create(v, version, u.id, req.expiresInDays).map(Right(_))
              ).flatMap {
                case Left(e) => IO.pure(Left(e))
                case Right((l, secret)) =>
                  audit.record(
                    p.actor,
                    "share.create",
                    v.workspace,
                    Some(v.project),
                    Some(l.id),
                    Some(s"${v.id}@$version")
                  )
                    .as(Right(ShareLinkCreated(l.id, s"$baseUrl/s/$secret", secret, l.expiresAt)))
              }
          }
        }
    )
  private val listShareLinks =
    Stage4.listShareLinks.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      (ws, proj) =>
        withE(authz.requireMember(p, ws))(_ =>
          withE(projectOf(p, ws, proj))(key => links.list(key).map(ls => Right(ls.map(_.summary))))
        )
    )
  private val revokeShareLink =
    Stage4.revokeShareLink.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      id =>
        withE(linkOf(p, id)) { l =>
          withE(authz.requireMember(p, l.workspace)) { (u, role) =>
            if l.createdBy != u.id && !role.isAdmin then
              IO.pure(Left(ApiError(
                "forbidden",
                "only the link's creator or a workspace admin may revoke it"
              )))
            else
              links.revoke(id) *>
                audit.record(
                  p.actor,
                  "share.revoke",
                  l.workspace,
                  Some(l.project),
                  Some(id),
                  None
                ).as(Right(()))
          }
        }
    )

  /** A presented share secret → its link, or 401 (unknown) / 410 (revoked or expired). */
  private def liveLink(secret: String): Res[(ShareLinkRecord, ViewRecord)] =
    links.resolve(secret).flatMap {
      case None => IO.pure(Left(ApiError("unauthorized", "unknown share link")))
      case Some(l) =>
        IO.realTimeInstant.flatMap { now =>
          if !l.usable(now) then
            IO.pure(Left(ApiError("revoked", "this link has been revoked or has expired")))
          else
            views.get(l.view).map(_.toRight(notFound("the shared view no longer exists")).map(l ->
              _))
        }
    }
  private val openShare = Stage4.openShare.serverLogic[IO] { secret =>
    withE(liveLink(secret)) { (l, v) =>
      v.version(l.version) match
        case None => IO.pure(Left(notFound("the shared view version no longer exists")))
        case Some(ver) =>
          pub.revision(v.revision).map(_.map { rd =>
            // link viewers fetch renditions through the share routes, never the member routes
            val rends = rd.renditions.map(r =>
              r.copy(
                headerUrl = s"$baseUrl/api/v1/share/$secret/renditions/${r.assetId}/header",
                payloadUrl = s"$baseUrl/api/v1/share/$secret/renditions/${r.assetId}/payload"
              )
            )
            // the presentation subset: what the page renders, nothing of the record behind it
            val presentation = rd.copy(
              parent = None,
              message = None,
              committedAt = rd.committedAt.take(10),
              manifest = SharedProjection.of(rd.manifest),
              renditions = rends
            )
            SharedView(
              SharedViewRef(v.id, v.name, v.revision, v.project),
              SharedVersion(ver.version, ver.state, ver.savedAt),
              presentation,
              l.expiresAt
            )
          })
    }
  }
  private val shareHeader = Stage4.shareRenditionHeader.serverLogic[IO]((secret, asset) =>
    withE(liveLink(secret))((_, v) => header(v.revision, asset))
  )
  private val sharePayload = Stage4.shareRenditionPayload.serverLogic[IO]((secret, asset) =>
    withE(liveLink(secret))((_, v) => readIf(v.revision, asset, ingestion.payloadPath))
  )

  // ------------------------------------------------------------------ provenance and audit

  private val provenance =
    Stage4.provenance.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
      rev =>
        withE(revisionOf(p, rev)) { _ =>
          ProvenanceModel.cached(
            data,
            rev,
            pub.revision(rev).flatMap(d =>
              IO.fromEither(d.leftMap(e => IllegalStateException(e.message)))
            ).map(_.manifest)
          ).map(Right(_))
        }
    )
  private val auditLog = Stage4.audit.serverSecurityLogic[Principal, IO](resolve).serverLogic(p =>
    ws => withE(authz.requireAdmin(p, ws))(_ => audit.list(ws).map(Right(_)))
  )

  val routes: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List(
      health,
      create,
      putObject,
      putManifest,
      commit,
      project,
      revision,
      renditionHeader,
      renditionPayload,
      login,
      logout,
      revokeTokens,
      whoami,
      deviceStart,
      devicePoll,
      deviceApprove,
      deviceDeny,
      addMember,
      listMembers,
      createCredential,
      listCredentials,
      revokeCredential,
      saveView,
      updateView,
      getView,
      listViews,
      createShareLink,
      listShareLinks,
      revokeShareLink,
      openShare,
      shareHeader,
      sharePayload,
      provenance,
      auditLog
    )
  )

object Routes:
  val openApiYaml: String =
    OpenAPIDocsInterpreter().toOpenAPI(
      Protocol.all ++ Stage4.all,
      "Neuropublish control plane",
      "0.1"
    ).toYaml
