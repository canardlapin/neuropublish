package neuropublish.backend

import cats.effect.IO
import neuropublish.api.{Endpoints, Health}
import neuropublish.protocol.ProtocolVersion
import org.http4s.HttpRoutes
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.http4s.Http4sServerInterpreter

object Routes:
  val health: Health = Health("ok", ProtocolVersion.current.render, "neuropublish-backend")

  /** The OpenAPI document generated from the shared endpoint definitions. */
  val openApiYaml: String =
    OpenAPIDocsInterpreter().toOpenAPI(Endpoints.all, "Neuropublish control plane", "0.1").toYaml

  val routes: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      Endpoints.health.serverLogicSuccess(_ => IO.pure(health))
    )
