package neuropublish.backend

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import cats.effect.std.UUIDGen
import com.comcast.ip4s.*
import fs2.io.file.Path
import neuropublish.persistence.{DbConfig, PgStores}
import org.http4s.{HttpRoutes, StaticFile}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.*

/** Configuration by environment:
  *
  *   - NP_DATA_DIR (default ./data); NP_PORT (default 8080); NP_STATIC_DIR (built frontend;
  *     optional)
  *   - NP_DATABASE_URL (+ NP_DATABASE_USER / NP_DATABASE_PASSWORD): when set, every record store is
  *     PostgreSQL (Flyway migrations run at start); unset, the local-fs JSON stores under
  *     NP_DATA_DIR. Objects and renditions stay under NP_DATA_DIR either way (Stage 2 object
  *     store).
  *   - NP_BASE_URL (default http://127.0.0.1:$NP_PORT): the public origin — share URLs, device
  *     verification URIs, rendition URLs; an `https://` value marks cookies `Secure`
  *   - NP_WORKSPACE / NP_PROJECT (default rotman / sherlock — the bootstrap workspace and project)
  *   - NP_OWNER_EMAIL / NP_OWNER_PASSWORD (default owner@example.org / owner-dev-password): the
  *     local-provider user created as `owner` of the bootstrap workspace if absent (Stage 4)
  *   - NP_LEGACY_TOKEN: deprecated Stage 1 static bearer token (was NP_TOKEN; renamed because the
  *     CLI now reads NP_TOKEN as its own bearer). Unset by default; when set, it still authorizes
  *     publishing and reads with no identity. Remove once every client uses `npub login` or a
  *     publisher credential.
  *
  * Subcommands: none → serve; `reindex` → rebuild the PostgreSQL read model from the stored
  * manifests and exit (requires NP_DATABASE_URL).
  */
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val env = sys.env
    val data = Path(env.getOrElse("NP_DATA_DIR", "data"))
    val db = DbConfig.fromEnv(env)
    args match
      case "reindex" :: Nil => reindex(data, db)
      case Nil => serve(env, data, db).as(ExitCode.Success)
      case other =>
        IO.println(s"unknown arguments: ${other.mkString(" ")}; usage: [reindex]").as(ExitCode(2))

  private def reindex(data: Path, db: Option[DbConfig]): IO[ExitCode] = db match
    case None =>
      IO.println("reindex needs NP_DATABASE_URL: the local-fs stores keep no read model")
        .as(ExitCode(2))
    case Some(cfg) =>
      PgStores.resource(cfg).use { pg =>
        val objects = Server.objects(data)
        pg.reindex(objects.get).run.flatMap(r =>
          IO.println(
            s"reindex: ${r.scanned} revisions scanned, ${r.indexed} indexed" +
              (if r.missing.isEmpty then ""
               else s"; manifest missing for ${r.missing.mkString(", ")}")
          ).as(if r.missing.isEmpty then ExitCode.Success else ExitCode(1))
        )
      }

  private def serve(env: Map[String, String], data: Path, db: Option[DbConfig]): IO[Unit] =
    val legacy = env.get("NP_LEGACY_TOKEN").filter(_.nonEmpty)
    val key =
      ProjectKey(env.getOrElse("NP_WORKSPACE", "rotman"), env.getOrElse("NP_PROJECT", "sherlock"))
    val port = Port.fromString(env.getOrElse("NP_PORT", "8080")).getOrElse(port"8080")
    val base = env.get("NP_BASE_URL").map(_.trim.stripSuffix("/")).filter(_.nonEmpty)
      .getOrElse(s"http://127.0.0.1:$port")
    val static = env.get("NP_STATIC_DIR").map(Path(_))
    val ownerEmail = env.getOrElse("NP_OWNER_EMAIL", Server.DefaultOwnerEmail)
    val ownerPassword = env.getOrElse("NP_OWNER_PASSWORD", Server.DefaultOwnerPassword)
    val stores = db.fold(Resource.eval(Server.Stores.localFs(data)))(Server.Stores.postgres)
    stores.use { s =>
      Server.build(s, data, key, base, ownerEmail, ownerPassword, legacy, Nil).flatMap { api =>
        val app = static.fold(api)(dir => api <+> Server.spa(dir))
        EmberServerBuilder.default[IO].withHost(host"127.0.0.1").withPort(port)
          .withHttpApp(CORS.policy.withAllowOriginAll.withAllowCredentials(false)(app.orNotFound))
          .build
          .evalTap(srv =>
            IO.println(
              s"neuropublish backend on ${srv.address}; base $base; project ${key.render}; owner $ownerEmail; data $data; stores ${s.describe}" +
                legacy.fold("")(_ => "; deprecated NP_LEGACY_TOKEN static token enabled")
            )
          )
          .useForever
      }
    }

object Server:
  val DefaultOwnerEmail = "owner@example.org"
  val DefaultOwnerPassword = "owner-dev-password"

  /** A workspace/project to create at start with a local-provider owner (operator bootstrap; the
    * alpha has no self-serve workspace creation, ADR 0004).
    */
  final case class Bootstrap(key: ProjectKey, ownerEmail: String, ownerPassword: String)

  /** The record stores behind the routes: local-fs JSON (the default) or PostgreSQL. */
  final case class Stores(
      revisions: RevisionStore,
      identity: Identity,
      members: Members,
      sessions: Sessions,
      tokens: UserTokens,
      credentials: Credentials,
      views: Views,
      links: ShareLinks,
      audit: Audit,
      describe: String
  )
  object Stores:
    def localFs(data: Path): IO[Stores] =
      for
        revisions <- LocalRevisionStore(data)
        identity <- LocalIdentity(data)
        members <- LocalMembers(data)
        views <- LocalViews(data)
        audit <- LocalAudit(data)
      yield Stores(
        revisions,
        identity,
        members,
        LocalSessions(data / "sessions"),
        LocalUserTokens(data / "tokens"),
        LocalCredentials(data / "credentials"),
        views,
        LocalShareLinks(data / "links"),
        audit,
        s"local-fs"
      )

    /** Runs the Flyway migrations, then opens the pool. */
    def postgres(cfg: DbConfig): Resource[IO, Stores] = PgStores.resource(cfg).map(fromPg)

    def fromPg(pg: PgStores): Stores =
      Stores(
        pg.revisions,
        pg.identity,
        pg.members,
        pg.sessions,
        pg.tokens,
        pg.credentials,
        pg.views,
        pg.links,
        pg.audit,
        "postgresql"
      )

  /** The content-addressed object store under `data` (manifests, assets). */
  def objects(data: Path): ObjectStore = ObjectStore.LocalFs(data / "objects")

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

  /** Local-fs stores under `data`; see the [[Stores]] overload. */
  def build(
      data: Path,
      bootstrap: ProjectKey,
      baseUrl: String,
      ownerEmail: String = DefaultOwnerEmail,
      ownerPassword: String = DefaultOwnerPassword,
      legacyToken: Option[String] = None,
      extra: List[Bootstrap] = Nil
  ): IO[HttpRoutes[IO]] =
    Stores.localFs(data).flatMap(
      build(_, data, bootstrap, baseUrl, ownerEmail, ownerPassword, legacyToken, extra)
    )

  /** Wires the routes over `stores` (objects, renditions, and the provenance cache stay under
    * `data`), creates the bootstrap project (and any `extra` ones), and ensures each owner user and
    * membership exist. `legacyToken` enables the deprecated NP_TOKEN path.
    */
  def build(
      stores: Stores,
      data: Path,
      bootstrap: ProjectKey,
      baseUrl: String,
      ownerEmail: String,
      ownerPassword: String,
      legacyToken: Option[String],
      extra: List[Bootstrap]
  ): IO[HttpRoutes[IO]] =
    val all = Bootstrap(bootstrap, ownerEmail, ownerPassword) :: extra
    val revisions = stores.revisions
    for
      _ <- all.traverse_(b => revisions.createProject(b.key))
      objs = objects(data)
      ingestion = Ingestion(objs, data)
      uploads <- Ref.of[IO, Map[String, UploadSession]](Map.empty)
      pub = Publication(
        objs,
        revisions,
        ingestion,
        uploads,
        baseUrl,
        UUIDGen[IO].randomUUID.map(_.toString.take(8))
      )
      _ <- all.traverse_ { b =>
        stores.identity
          .ensureLocalUser(b.ownerEmail, b.ownerEmail.takeWhile(_ != '@'), b.ownerPassword)
          .flatMap(owner =>
            stores.members.role(b.key.workspace, owner.id).flatMap {
              case Some(_) => IO.unit
              case None => stores.members.set(b.key.workspace, owner.id, Role.Owner)
            }
          )
      }
      device <- DeviceFlow.inMemory(stores.tokens)
      authz = Authz(
        stores.identity,
        stores.members,
        stores.sessions,
        stores.tokens,
        stores.credentials,
        legacyToken
      )
    yield Routes(
      pub,
      revisions,
      ingestion,
      stores.identity,
      stores.members,
      stores.sessions,
      stores.tokens,
      device,
      stores.credentials,
      stores.views,
      stores.links,
      stores.audit,
      authz,
      data,
      baseUrl
    ).routes
