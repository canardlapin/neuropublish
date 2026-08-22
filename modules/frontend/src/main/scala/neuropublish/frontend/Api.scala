package neuropublish.frontend

import io.circe.Decoder
import neuropublish.api.*
import neuropublish.api.Protocol.given
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** Stage 1 read-side client over fetch. Same DTOs as the server (api-contract). */
final class Api(base: String)(using ExecutionContext):
  private def json[A: Decoder](path: String): Future[A] =
    dom.fetch(s"$base$path").toFuture.flatMap { r =>
      if !r.ok then Future.failed(RuntimeException(s"${r.status} ${r.statusText} for $path"))
      else r.text().toFuture.flatMap(t => Future.fromTry(_root_.io.circe.parser.decode[A](t).toTry))
    }
  private def bytes(url: String): Future[Array[Byte]] =
    dom.fetch(url).toFuture.flatMap { r =>
      if !r.ok then Future.failed(RuntimeException(s"${r.status} for $url"))
      else r.arrayBuffer().toFuture.map(ab => new Int8Array(ab).toArray)
    }
  private def text(url: String): Future[String] =
    dom.fetch(url).toFuture.flatMap(r => r.text().toFuture)

  def project(ws: String, p: String): Future[ProjectSummary] =
    json[ProjectSummary](s"/api/v1/workspaces/$ws/projects/$p")
  def revision(id: String): Future[RevisionDetail] = json[RevisionDetail](s"/api/v1/revisions/$id")
  def rendition(ref: RenditionRef): Future[(String, Array[Byte])] =
    text(ref.headerUrl).zip(bytes(ref.payloadUrl))
