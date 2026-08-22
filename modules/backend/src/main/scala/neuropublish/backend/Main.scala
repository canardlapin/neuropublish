package neuropublish.backend

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"8080")
      .withHttpApp(Routes.routes.orNotFound)
      .build
      .evalTap(s => IO.println(s"neuropublish backend listening on ${s.address}"))
      .useForever
