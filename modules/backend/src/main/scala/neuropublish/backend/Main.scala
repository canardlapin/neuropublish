package neuropublish.backend

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import cats.effect.std.UUIDGen
import com.comcast.ip4s.*
import fs2.io.file.Path
import org.http4s.{HttpRoutes, StaticFile}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.*

/** Configuration by environment:
  *
  *   - NP_DATA_DIR (default ./data); NP_PORT (default 8080); NP_STATIC_DIR (built frontend;
  *     optional)
  *   - NP_BASE_URL (default http://127.0.0.1:$NP_PORT): the public origin — share URLs, device
  *     verification URIs, rendition URLs; an `https://` value marks cookies `Secure`
  *   - NP_WORKSPACE / NP_PROJECT (default rotman / sherlock — the bootstrap workspace and project)
  *   - NP_OWNER_EMAIL / NP_OWNER_PASSWORD (default owner@example.org / owner-dev-password): the
  *     local-provider user created as `owner` of the bootstrap workspace if absent (Stage 4)
  *   - NP_LEGACY_TOKEN: deprecated Stage 1 static bearer token (was NP_TOKEN; renamed because the
  *     CLI now reads NP_TOKEN as its own bearer). Unset by default; when set, it still authorizes
  *     publishing and reads with no identity. Remove once every client uses `npub login` or a
  *     publisher credential.
  */
object Main extends IOApp.Simple:
  def run: IO[Unit] =
    val env = sys.env
    val data = Path(env.getOrElse("NP_DATA_DIR", "data"))
    val legacy = env.get("NP_LEGACY_TOKEN").filter(_.nonEmpty)
    val key =
      ProjectKey(env.getOrElse("NP_WORKSPACE", "rotman"), env.getOrElse("NP_PROJECT", "sherlock"))
    val port = Port.fromString(env.getOrElse("NP_PORT", "8080")).getOrElse(port"8080")
    val base = env.get("NP_BASE_URL").map(_.trim.stripSuffix("/")).filter(_.nonEmpty)
      .getOrElse(s"http://127.0.0.1:$port")
    val static = env.get("NP_STATIC_DIR").map(Path(_))
    val ownerEmail = env.getOrElse("NP_OWNER_EMAIL", Server.DefaultOwnerEmail)
    val ownerPassword = env.getOrElse("NP_OWNER_PASSWORD", Server.DefaultOwnerPassword)
    Server.build(data, key, base, ownerEmail, ownerPassword, legacy).flatMap { api =>
      val app = static.fold(api)(dir => api <+> Server.spa(dir))
      EmberServerBuilder.default[IO].withHost(host"127.0.0.1").withPort(port)
        .withHttpApp(CORS.policy.withAllowOriginAll.withAllowCredentials(false)(app.orNotFound))
        .build
        .evalTap(s =>
          IO.println(
            s"neuropublish backend on ${s.address}; base $base; project ${key.render}; owner $ownerEmail; data $data" +
              legacy.fold("")(_ => "; deprecated NP_LEGACY_TOKEN static token enabled")
          )
        )
        .useForever
    }

object Server:
  val DefaultOwnerEmail = "owner@example.org"
  val DefaultOwnerPassword = "owner-dev-password"

  /** A workspace/project to create at start with a local-provider owner (operator bootstrap; the
    * alpha has no self-serve workspace creation, ADR 0004).
    */
  final case class Bootstrap(key: ProjectKey, ownerEmail: String, ownerPassword: String)

  /** Built frontend: real files from `dir`, everything else falls back to index.html. */
  def spa(dir: Path): HttpRoutes[IO] =
    val files = fileService[IO](FileService.Config(dir.toString))
    val fallback = HttpRoutes.of[IO] {
      case req if req.method == org.http4s.Method.GET =>
        StaticFile.fromPath(
          dir / "index.html",
          Some(req)
        ).getOrElse(org.http4s.Response.notFound[IO])
    }
    files <+> fallback

  /** Stage 1 signature, kept for existing callers: `token` becomes the deprecated static token. */
  def build(data: Path, token: String, bootstrap: ProjectKey, baseUrl: String): IO[HttpRoutes[IO]] =
    build(data, bootstrap, baseUrl, legacyToken = Some(token))

  /** Wires every store under `data`, creates the bootstrap project (and any `extra` ones), and
    * ensures each owner user and membership exist. `legacyToken` enables the deprecated NP_TOKEN
    * path.
    */
  def build(
      data: Path,
      bootstrap: ProjectKey,
      baseUrl: String,
      ownerEmail: String = DefaultOwnerEmail,
      ownerPassword: String = DefaultOwnerPassword,
      legacyToken: Option[String] = None,
      extra: List[Bootstrap] = Nil
  ): IO[HttpRoutes[IO]] =
    val all = Bootstrap(bootstrap, ownerEmail, ownerPassword) :: extra
    for
      revisions <- RevisionStore.localFs(data)
      _ <- all.traverse_(b => revisions.createProject(b.key))
      objects = ObjectStore.LocalFs(data / "objects")
      ingestion = Ingestion(objects, data)
      uploads <- Ref.of[IO, Map[String, UploadSession]](Map.empty)
      pub = Publication(
        objects,
        revisions,
        ingestion,
        uploads,
        baseUrl,
        UUIDGen[IO].randomUUID.map(_.toString.take(8))
      )
      identity <- Identity.local(data)
      members <- Members.localFs(data)
      _ <- all.traverse_ { b =>
        identity.ensureLocalUser(b.ownerEmail, b.ownerEmail.takeWhile(_ != '@'), b.ownerPassword)
          .flatMap(owner =>
            members.role(b.key.workspace, owner.id).flatMap {
              case Some(_) => IO.unit
              case None => members.set(b.key.workspace, owner.id, Role.Owner)
            }
          )
      }
      sessions = Sessions(data / "sessions")
      tokens = UserTokens(data / "tokens")
      device <- DeviceFlow.inMemory(tokens)
      credentials = Credentials(data / "credentials")
      views <- Views.localFs(data)
      links = ShareLinks(data / "links")
      audit <- Audit.localFs(data)
      authz = Authz(identity, members, sessions, tokens, credentials, legacyToken)
    yield Routes(
      pub,
      revisions,
      ingestion,
      identity,
      members,
      sessions,
      tokens,
      device,
      credentials,
      views,
      links,
      audit,
      authz,
      data,
      baseUrl
    ).routes
