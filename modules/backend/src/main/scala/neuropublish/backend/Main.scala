package neuropublish.backend

import cats.effect.{IO, IOApp, Ref}
import cats.syntax.all.*
import cats.effect.std.UUIDGen
import com.comcast.ip4s.*
import fs2.io.file.Path
import org.http4s.{HttpRoutes, Request, StaticFile}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.*

/** Stage 1 thin spine. Configuration by environment: NP_DATA_DIR (default ./data), NP_TOKEN
  * (default "dev-token"), NP_WORKSPACE / NP_PROJECT (default rotman / sherlock — the bootstrap
  * workspace), NP_PORT (default 8080), NP_STATIC_DIR (built frontend to serve; optional).
  */
object Main extends IOApp.Simple:
  def run: IO[Unit] =
    val env = sys.env
    val data = Path(env.getOrElse("NP_DATA_DIR", "data"))
    val token = env.getOrElse("NP_TOKEN", "dev-token")
    val key =
      ProjectKey(env.getOrElse("NP_WORKSPACE", "rotman"), env.getOrElse("NP_PROJECT", "sherlock"))
    val port = Port.fromString(env.getOrElse("NP_PORT", "8080")).getOrElse(port"8080")
    val base = s"http://127.0.0.1:$port"
    val static = env.get("NP_STATIC_DIR").map(Path(_))
    Server.build(data, token, key, base).flatMap { api =>
      val app = static.fold(api)(dir => api <+> Server.spa(dir))
      EmberServerBuilder.default[IO].withHost(host"127.0.0.1").withPort(port)
        .withHttpApp(CORS.policy.withAllowOriginAll(app.orNotFound))
        .build
        .evalTap(s =>
          IO.println(s"neuropublish backend on ${s.address}; project ${key.render}; data $data")
        )
        .useForever
    }

object Server:
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

  def build(
      data: Path,
      token: String,
      bootstrap: ProjectKey,
      baseUrl: String
  ): IO[org.http4s.HttpRoutes[IO]] =
    for
      revisions <- RevisionStore.localFs(data)
      _ <- revisions.createProject(bootstrap)
      objects = ObjectStore.LocalFs(data / "objects")
      ingestion = Ingestion(objects, data)
      sessions <- Ref.of[IO, Map[String, UploadSession]](Map.empty)
      pub = Publication(
        objects,
        revisions,
        ingestion,
        sessions,
        baseUrl,
        UUIDGen[IO].randomUUID.map(_.toString.take(8))
      )
    yield Routes(pub, ingestion, token).routes
