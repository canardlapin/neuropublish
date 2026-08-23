package neuropublish.frontend

import io.circe.{Decoder, Json}
import io.circe.syntax.*
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.api.Stage4.given
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** A failed call: the server's `ApiError` (code/message) with its HTTP status, so the shell can
  * route 401 to sign-in and 410 to the revoked-link state. `message` is what the user sees.
  */
final case class ApiFailure(status: Int, code: String, message: String)
    extends RuntimeException(message)

/** Read/write client over fetch; same DTOs as the server (api-contract). Every request carries the
  * session cookie (`credentials: include`); there is no token in the browser.
  */
final class Api(base: String)(using ExecutionContext):
  private def init(method: String, body: Option[Json]): dom.RequestInit =
    val r = new dom.RequestInit {}
    r.method = method.asInstanceOf[dom.HttpMethod]
    r.credentials = dom.RequestCredentials.include
    body.foreach { b =>
      r.body = b.noSpaces
      r.headers = js.Dictionary("Content-Type" -> "application/json")
    }
    r

  private def fail(r: dom.Response, path: String): Future[Nothing] =
    r.text().toFuture.flatMap { t =>
      val e = _root_.io.circe.parser.decode[ApiError](t).toOption
      Future.failed(ApiFailure(
        r.status,
        e.map(_.code).getOrElse(""),
        e.map(_.message).getOrElse(s"${r.status} ${r.statusText} for $path")
      ))
    }

  private def call(method: String, path: String, body: Option[Json]): Future[dom.Response] =
    dom.fetch(s"$base$path", init(method, body)).toFuture.flatMap(r =>
      if r.ok then Future.successful(r) else fail(r, path)
    )

  private def json[A: Decoder](method: String, path: String, body: Option[Json] = None): Future[A] =
    call(method, path, body).flatMap(_.text().toFuture).flatMap(t =>
      Future.fromTry(_root_.io.circe.parser.decode[A](t).toTry)
    )
  private def unit(method: String, path: String, body: Option[Json] = None): Future[Unit] =
    call(method, path, body).map(_ => ())
  private def bytes(url: String): Future[Array[Byte]] =
    call("GET", url.stripPrefix(base), None).flatMap(_.arrayBuffer().toFuture).map(ab =>
      new Int8Array(ab).toArray
    )
  private def text(url: String): Future[String] =
    call("GET", url.stripPrefix(base), None).flatMap(_.text().toFuture)

  private def enc(s: String) = js.URIUtils.encodeURIComponent(s)

  // ---- project and revision reads (Stage 1) ----
  def project(ws: String, p: String): Future[ProjectSummary] =
    json[ProjectSummary]("GET", s"/api/v1/workspaces/${enc(ws)}/projects/${enc(p)}")
  def revision(id: String): Future[RevisionDetail] =
    json[RevisionDetail]("GET", s"/api/v1/revisions/${enc(id)}")
  def rendition(ref: RenditionRef): Future[(String, Array[Byte])] =
    text(ref.headerUrl).zip(bytes(ref.payloadUrl))

  // ---- identity ----
  def login(email: String, password: String): Future[Me] =
    json[Me]("POST", "/api/v1/auth/login", Some(LoginRequest(email, password).asJson))
  def logout(): Future[Unit] = unit("POST", "/api/v1/auth/logout")
  def me(): Future[Me] = json[Me]("GET", "/api/v1/auth/me")
  def deviceApprove(userCode: String): Future[Unit] =
    unit("POST", "/api/v1/auth/device/approve", Some(DeviceApprove(userCode).asJson))

  // ---- saved views ----
  def saveView(revision: String, name: String, state: Json): Future[SavedViewDetail] =
    json[SavedViewDetail](
      "POST",
      s"/api/v1/revisions/${enc(revision)}/views",
      Some(SaveView(name, state).asJson)
    )
  def updateView(view: String, state: Json): Future[SavedViewDetail] =
    json[SavedViewDetail]("PUT", s"/api/v1/views/${enc(view)}", Some(UpdateView(state).asJson))
  def getView(view: String): Future[SavedViewDetail] =
    json[SavedViewDetail]("GET", s"/api/v1/views/${enc(view)}")
  def listViews(revision: String): Future[List[SavedViewSummary]] =
    json[List[SavedViewSummary]]("GET", s"/api/v1/revisions/${enc(revision)}/views")

  // ---- share links ----
  def createShareLink(view: String, version: Int, expiresInDays: Option[Int])
      : Future[ShareLinkCreated] =
    json[ShareLinkCreated](
      "POST",
      s"/api/v1/views/${enc(view)}/versions/$version/links",
      Some(CreateShareLink(expiresInDays).asJson)
    )
  def listShareLinks(ws: String, p: String): Future[List[ShareLinkSummary]] =
    json[List[ShareLinkSummary]]("GET", s"/api/v1/workspaces/${enc(ws)}/projects/${enc(p)}/links")
  def revokeShareLink(link: String): Future[Unit] = unit("DELETE", s"/api/v1/links/${enc(link)}")

  /** Public: no account; the secret authorizes exactly that view's revision. */
  def openShare(secret: String): Future[SharedView] =
    json[SharedView]("GET", s"/api/v1/share/${enc(secret)}")
  def shareRendition(secret: String, asset: String): Future[(String, Array[Byte])] =
    val p = s"/api/v1/share/${enc(secret)}/renditions/${enc(asset)}"
    text(s"$p/header").zip(bytes(s"$p/payload"))

  // ---- provenance read model ----
  def provenance(revision: String): Future[Provenance] =
    json[Provenance]("GET", s"/api/v1/revisions/${enc(revision)}/provenance")
