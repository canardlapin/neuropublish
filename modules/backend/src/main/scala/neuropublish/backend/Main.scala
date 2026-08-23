package neuropublish.backend

import cats.effect.{ExitCode, IO, IOApp, Resource}
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
  *     NP_DATA_DIR.
  *   - NP_S3_BUCKET (+ NP_S3_ENDPOINT, NP_S3_REGION, NP_S3_ACCESS_KEY, NP_S3_SECRET_KEY,
  *     NP_S3_PATH_STYLE): S3-compatible object store with presigned transfers; unset, objects and
  *     renditions under NP_DATA_DIR, proxied by the control plane ([[ObjectStore.S3Config]])
  *   - NP_INGESTION=inline|worker (default inline): derive renditions in the commit, or enqueue for
  *     `neuropublish.ingestion.Main` ([[IngestionMode]])
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
  * manifests and exit (requires NP_DATABASE_URL); `gc --older-than 24h [--dry-run]` → delayed
  * orphan cleanup ([[Gc]]).
  */
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val env = sys.env
    val data = Path(env.getOrElse("NP_DATA_DIR", "data"))
    val db = DbConfig.fromEnv(env)
    args match
      case "reindex" :: Nil => reindex(data, db, env)
      case "gc" :: rest => gc(data, db, env, rest)
      case Nil => serve(env, data, db).as(ExitCode.Success)
      case other =>
        IO.println(s"unknown arguments: ${other.mkString(" ")}; usage: [reindex | gc]").as(
          ExitCode(2)
        )

  private def reindex(data: Path, db: Option[DbConfig], env: Map[String, String]): IO[ExitCode] =
    db match
      case None =>
        IO.println("reindex needs NP_DATABASE_URL: the local-fs stores keep no read model")
          .as(ExitCode(2))
      case Some(cfg) =>
        (PgStores.resource(cfg), Server.storage(data, env, db)).tupled.use { (pg, st) =>
          pg.reindex(st.objects.get).run.flatMap(r =>
            IO.println(
              s"reindex: ${r.scanned} revisions scanned, ${r.indexed} indexed" +
                (if r.missing.isEmpty then ""
                 else s"; manifest missing for ${r.missing.mkString(", ")}")
            ).as(if r.missing.isEmpty then ExitCode.Success else ExitCode(1))
          )
        }

  private def gc(
      data: Path,
      db: Option[DbConfig],
      env: Map[String, String],
      args: List[String]
  ): IO[ExitCode] =
    val dry = args.contains("--dry-run")
    val older = args.sliding(2).collectFirst { case List("--older-than", v) => v }.getOrElse("24h")
    Gc.parseDuration(older) match
      case Left(m) => IO.println(s"error  $m").as(ExitCode(2))
      case Right(d) =>
        val stores = db.fold(Resource.eval(Server.Stores.localFs(data)))(Server.Stores.postgres)
        (stores, Server.storage(data, env, db)).tupled.use { (rs, st) =>
          (for
            now <- IO.realTimeInstant
            r <- Gc.run(
              rs.revisions,
              st.objects,
              st.renditions,
              st.sessions,
              st.assets,
              rs.audit,
              d,
              dry,
              now
            )
            _ <- IO.println(
              s"gc${if dry then " (dry run)" else ""}  ${r.scanned} objects scanned, ${r.referenced} referenced, ${r.deleted.length} orphaned${
                  if dry then "" else " and deleted"
                }, ${r.renditionsDeleted.length} stale rendition sets, ${r.sessionsDropped} abandoned upload sessions"
            )
            _ <- r.deleted.traverse_(d =>
              IO.println(s"  ${if dry then "would delete" else "deleted"}  ${d.render}")
            )
          yield ExitCode.Success).recoverWith { case e: Throwable =>
            // refuse rather than delete on a partial view of what is referenced
            IO.println(s"gc refused: ${e.getMessage}").as(ExitCode(1))
          }
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
    (stores, Server.storage(data, env, db)).tupled.use { (s, st) =>
      Server.build(s, data, key, base, ownerEmail, ownerPassword, legacy, Nil, Some(st)).flatMap {
        api =>
          val app = static.fold(api)(dir => api <+> Server.spa(dir))
          EmberServerBuilder.default[IO].withHost(host"127.0.0.1").withPort(port)
            .withHttpApp(CORS.policy.withAllowOriginAll.withAllowCredentials(false)(app.orNotFound))
            .build
            .evalTap(srv =>
              IO.println(
                s"neuropublish backend on ${srv.address}; base $base; project ${key.render}; owner $ownerEmail; data $data; stores ${s.describe}; objects ${st.describe}; ingestion ${st.mode.toString.toLowerCase}" +
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

  /** The byte side of the deployment: the object store (local or S3), where renditions go, the
    * ingestion queue the control plane produces into and the worker consumes from, the persisted
    * upload sessions, and the per-workspace digest registry. Built once per process from the
    * environment by [[storage]].
    */
  final case class Storage(
      objects: ObjectStore,
      renditions: RenditionStore,
      queue: IngestionQueue,
      sessions: UploadSessions,
      assets: WorkspaceAssets,
      mode: IngestionMode,
      describe: String
  )

  /** Local data-dir storage; the default for tests and `scripts/e2e.sh`. */
  def localStorage(data: Path, mode: IngestionMode = IngestionMode.Inline): Storage =
    Storage(
      objects(data),
      RenditionStore.LocalFs(data),
      LocalIngestionQueue(data / "queue"),
      LocalUploadSessions(data / "upload-sessions"),
      LocalWorkspaceAssets(data / "workspace-assets"),
      mode,
      s"local $data/objects"
    )

  /** S3 mode when NP_S3_BUCKET is set, else local; NP_INGESTION picks the ingestion mode. With `db`
    * (NP_DATABASE_URL) the queue, upload sessions, and the workspace asset registry live in
    * PostgreSQL, so the only local state left is the data dir of local object mode. The worker's
    * `Main` builds its storage here too, so producer and consumer always agree.
    */
  def storage(data: Path, env: Map[String, String], db: Option[DbConfig]): Resource[IO, Storage] =
    val mode = Ingestion.modeFromEnv(env)
    val local = localStorage(data, mode)
    val records: Resource[IO, Storage] = db match
      case None => Resource.pure(local)
      case Some(cfg) =>
        PgStores.resource(cfg).map(pg =>
          local.copy(queue = pg.queue, sessions = pg.uploadSessions, assets = pg.workspaceAssets)
        )
    ObjectStore.S3Config.fromEnv(env) match
      case None => records
      case Some(c) =>
        (records, ObjectStore.s3(c).evalTap(_.ensureBucket)).mapN((st, s3) =>
          st.copy(
            objects = s3,
            renditions = RenditionStore.of(s3, data),
            describe = s"s3 ${c.endpoint.getOrElse("aws")}/${c.bucket}"
          )
        )

  /** The content-addressed local object store under `data` (manifests, assets). */
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
      extra: List[Bootstrap] = Nil,
      storage: Option[Storage] = None
  ): IO[HttpRoutes[IO]] =
    Stores.localFs(data).flatMap(
      build(_, data, bootstrap, baseUrl, ownerEmail, ownerPassword, legacyToken, extra, storage)
    )

  /** Wires the routes over `stores` and `storage` (default: local under `data`; the provenance
    * cache stays under `data`), creates the bootstrap project (and any `extra` ones), and ensures
    * each owner user and membership exist. `legacyToken` enables the deprecated NP_TOKEN path.
    */
  def build(
      stores: Stores,
      data: Path,
      bootstrap: ProjectKey,
      baseUrl: String,
      ownerEmail: String,
      ownerPassword: String,
      legacyToken: Option[String],
      extra: List[Bootstrap],
      storage: Option[Storage]
  ): IO[HttpRoutes[IO]] =
    val all = Bootstrap(bootstrap, ownerEmail, ownerPassword) :: extra
    val revisions = stores.revisions
    val st = storage.getOrElse(localStorage(data))
    for
      _ <- all.traverse_(b => revisions.createProject(b.key))
      ingestion = Ingestion(st.objects, st.renditions, st.queue, st.mode)
      pub = Publication(
        st.objects,
        revisions,
        ingestion,
        st.assets,
        st.sessions,
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
