package neuropublish.backend

import cats.effect.{ExitCode, IO, IOApp, Resource}
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
  *   - NP_S3_BUCKET (+ NP_S3_ENDPOINT, NP_S3_REGION, NP_S3_ACCESS_KEY, NP_S3_SECRET_KEY,
  *     NP_S3_PATH_STYLE): S3-compatible object store; unset = objects under the data dir
  *     ([[ObjectStore.S3Config]])
  *   - NP_INGESTION=inline|worker (default inline): derive renditions in the commit, or enqueue for
  *     `neuropublish.ingestion.Main` ([[IngestionMode]])
  *   - NP_LEGACY_TOKEN: deprecated Stage 1 static bearer token (was NP_TOKEN; renamed because the
  *     CLI now reads NP_TOKEN as its own bearer). Unset by default; when set, it still authorizes
  *     publishing and reads with no identity. Remove once every client uses `npub login` or a
  *     publisher credential.
  *
  * Subcommands: none → serve; `gc --older-than 24h [--dry-run]` → orphan cleanup ([[Gc]]).
  */
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] = args match
    case "gc" :: rest => gc(rest)
    case Nil => serve.as(ExitCode.Success)
    case other => IO.println(s"unknown arguments: ${other.mkString(" ")}").as(ExitCode.Error)

  private def serve: IO[Unit] =
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
    Server.stores(data, env).use { stores =>
      Server.build(
        data,
        key,
        base,
        ownerEmail,
        ownerPassword,
        legacy,
        stores = Some(stores)
      ).flatMap {
        api =>
          val app = static.fold(api)(dir => api <+> Server.spa(dir))
          EmberServerBuilder.default[IO].withHost(host"127.0.0.1").withPort(port)
            .withHttpApp(CORS.policy.withAllowOriginAll.withAllowCredentials(false)(app.orNotFound))
            .build
            .evalTap(s =>
              IO.println(
                s"neuropublish backend on ${s.address}; base $base; project ${key.render}; owner $ownerEmail; data $data; objects ${stores.describe}; ingestion ${stores.mode.toString.toLowerCase}" +
                  legacy.fold("")(_ => "; deprecated NP_LEGACY_TOKEN static token enabled")
              )
            )
            .useForever
      }
    }

  private def gc(args: List[String]): IO[ExitCode] =
    val dry = args.contains("--dry-run")
    val older = args.sliding(2).collectFirst { case List("--older-than", v) => v }.getOrElse("24h")
    Gc.parseDuration(older) match
      case Left(m) => IO.println(s"error  $m").as(ExitCode.Error)
      case Right(d) =>
        val data = Path(sys.env.getOrElse("NP_DATA_DIR", "data"))
        Server.stores(data, sys.env).use { st =>
          for
            audit <- Audit.localFs(data)
            now <- IO.realTimeInstant
            r <- Gc.run(data, st.objects, st.renditions, st.sessions, audit, d, dry, now)
            _ <- IO.println(
              s"gc${if dry then " (dry run)" else ""}  ${r.scanned} objects scanned, ${r.referenced} referenced, ${r.deleted.length} orphaned${
                  if dry then "" else " and deleted"
                }, ${r.renditionsDeleted.length} stale rendition sets"
            )
            _ <- r.deleted.traverse_(d =>
              IO.println(s"  ${if dry then "would delete" else "deleted"}  ${d.render}")
            )
          yield ExitCode.Success
        }

object Server:
  val DefaultOwnerEmail = "owner@example.org"
  val DefaultOwnerPassword = "owner-dev-password"

  /** A workspace/project to create at start with a local-provider owner (operator bootstrap; the
    * alpha has no self-serve workspace creation, ADR 0004).
    */
  final case class Bootstrap(key: ProjectKey, ownerEmail: String, ownerPassword: String)

  /** The storage wiring a process runs against: the object store (local or S3), where renditions
    * go, the ingestion queue the control plane produces into and the worker consumes from, and the
    * persisted upload sessions. Built once per process from the environment by [[stores]].
    */
  final case class Stores(
      objects: ObjectStore,
      renditions: RenditionStore,
      queue: IngestionQueue,
      sessions: UploadSessions,
      assets: WorkspaceAssets,
      mode: IngestionMode,
      describe: String
  )

  /** Local data-dir stores; the default for tests and `scripts/e2e.sh`. */
  def localStores(data: Path, mode: IngestionMode = IngestionMode.Inline): Stores =
    val objects = ObjectStore.LocalFs(data / "objects")
    Stores(
      objects,
      RenditionStore.LocalFs(data),
      // adapter point: IngestionQueue.postgres(...) once modules/persistence lands
      IngestionQueue.LocalFs(data / "queue"),
      UploadSessions(data / "upload-sessions"),
      WorkspaceAssets(data / "workspace-assets"),
      mode,
      s"local $data/objects"
    )

  /** S3 mode when NP_S3_BUCKET is set, else local; NP_INGESTION picks the ingestion mode. */
  def stores(data: Path, env: Map[String, String]): Resource[IO, Stores] =
    val mode = Ingestion.modeFromEnv(env)
    ObjectStore.S3Config.fromEnv(env) match
      case None => Resource.pure(localStores(data, mode))
      case Some(c) =>
        ObjectStore.s3(c).evalTap(_.ensureBucket).map(s3 =>
          localStores(data, mode).copy(
            objects = s3,
            renditions = RenditionStore.of(s3, data),
            describe = s"s3 ${c.endpoint.getOrElse("aws")}/${c.bucket}"
          )
        )

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
    * path. `stores` defaults to local inline storage.
    */
  def build(
      data: Path,
      bootstrap: ProjectKey,
      baseUrl: String,
      ownerEmail: String = DefaultOwnerEmail,
      ownerPassword: String = DefaultOwnerPassword,
      legacyToken: Option[String] = None,
      extra: List[Bootstrap] = Nil,
      stores: Option[Stores] = None
  ): IO[HttpRoutes[IO]] =
    val st = stores.getOrElse(localStores(data))
    val all = Bootstrap(bootstrap, ownerEmail, ownerPassword) :: extra
    for
      revisions <- RevisionStore.localFs(data)
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
