package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import neuropublish.api.*
import neuropublish.protocol.ProtocolVersion
import org.http4s.HttpRoutes
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.http4s.Http4sServerInterpreter

/** Stage 1 identity: one static bearer token (ADR 0004 schema is ready; Stage 4 adds real
  * identity).
  */
final class Routes(pub: Publication, ingestion: Ingestion, token: String):
  private def auth(t: String): IO[Either[ApiError, Unit]] =
    IO.pure(if t == token then Right(()) else Left(ApiError("unauthorized", "invalid token")))

  private val health = Endpoints.health.serverLogicSuccess(_ =>
    IO.pure(Health("ok", ProtocolVersion.current.render, "neuropublish-backend"))
  )

  private val create = Protocol.createUploadSession.serverSecurityLogic(auth).serverLogic(_ =>
    (ws, p, req) =>
      pub.createSession(ProjectKey(ws, p), req)
  )
  private val putObject = Protocol.uploadObject.serverSecurityLogic(auth).serverLogic(_ =>
    (s, d, b) =>
      pub.uploadObject(s, d, b)
  )
  private val putManifest = Protocol.uploadManifest.serverSecurityLogic(auth).serverLogic(_ =>
    (s, b) =>
      pub.uploadManifest(s, b)
  )
  private val commit =
    Protocol.commit.serverSecurityLogic(auth).serverLogic(_ => (s, req) => pub.commit(s, req))
  private val project = Protocol.project.serverLogic((ws, p) => pub.project(ProjectKey(ws, p)))
  private val revision = Protocol.revision.serverLogic(pub.revision)
  private val header = Protocol.renditionHeader.serverLogic((r, a) =>
    readIf(r, a, ingestion.headerPath).map(_.map(b => new String(b, "UTF-8")))
  )
  private val payload =
    Protocol.renditionPayload.serverLogic((r, a) => readIf(r, a, ingestion.payloadPath))

  private def readIf(
      rev: String,
      asset: String,
      path: (String, String) => Path
  ): IO[Either[ApiError, Array[Byte]]] =
    if !Ids.valid(rev) || !Ids.valid(asset) then
      IO.pure(Left(ApiError("not_found", "no such rendition")))
    else readIf(path(rev, asset))

  private def readIf(p: Path): IO[Either[ApiError, Array[Byte]]] =
    Files[IO].exists(p).flatMap {
      case false => IO.pure(Left(ApiError("not_found", "rendition not ready")))
      case true => Files[IO].readAll(p).compile.to(Array).map(Right(_))
    }

  val routes: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List(health, create, putObject, putManifest, commit, project, revision, header, payload)
  )

object Routes:
  val openApiYaml: String =
    OpenAPIDocsInterpreter().toOpenAPI(Protocol.all, "Neuropublish control plane", "0.1").toYaml
