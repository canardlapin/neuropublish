package neuropublish.backend

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.{Header, Headers, HttpApp}
import org.typelevel.ci.CIString

/** Browser security policy shared by the API and the served Scala.js application.
  *
  * Display controls currently set element styles at runtime, hence `style-src 'unsafe-inline'`;
  * scripts remain self-only. Cross-origin `https:` connections are required for presigned object
  * storage GET/PUT URLs. The private alpha terminates TLS at its ingress, so HSTS follows the
  * public `NP_BASE_URL`, not Ember's loopback connection.
  */
object SecurityHeaders:
  private val contentSecurityPolicy =
    "default-src 'self'; " +
      "base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; " +
      "script-src 'self'; " +
      "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
      "font-src 'self' https://fonts.gstatic.com; " +
      "img-src 'self' data: blob:; " +
      "connect-src 'self' https: http://127.0.0.1:*; " +
      "worker-src 'self' blob:"

  private def raw(name: String, value: String): Header.Raw =
    Header.Raw(CIString(name), value)

  def apply(app: HttpApp[IO], https: Boolean): HttpApp[IO] = Kleisli { request =>
    app.run(request).map { response =>
      val common = List(
        raw("Content-Security-Policy", contentSecurityPolicy),
        raw("Referrer-Policy", "same-origin"),
        raw("X-Content-Type-Options", "nosniff"),
        raw("X-Frame-Options", "DENY"),
        raw("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
      )
      val headers =
        if https then
          raw("Strict-Transport-Security", "max-age=31536000; includeSubDomains") :: common
        else common
      response.putHeaders(Headers(headers))
    }
  }
