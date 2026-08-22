package neuropublish.api

import io.circe.{Decoder, Encoder}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

final case class Health(status: String, protocol: String, service: String)

object Health:
  given Encoder[Health] = Encoder.forProduct3("status", "protocol", "service")(h =>
    (h.status, h.protocol, h.service)
  )
  given Decoder[Health] = Decoder.forProduct3("status", "protocol", "service")(Health.apply)

/** Control-plane endpoints, defined once for server, OpenAPI, and browser client. */
object Endpoints:
  private val base = endpoint.in("api" / "v1")

  val health: PublicEndpoint[Unit, Unit, Health, Any] =
    base.get
      .in("health")
      .out(jsonBody[Health])
      .description("Liveness and protocol version of the control plane.")

  val all: List[AnyEndpoint] = Protocol.all
