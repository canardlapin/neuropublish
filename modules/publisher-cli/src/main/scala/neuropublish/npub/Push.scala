package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

/** `npub push`: hash, negotiate, upload what is missing, commit.
  *
  * Each missing object follows its [[UploadInstruction]] verbatim (method, URL, headers), whether
  * that is the control plane (local mode) or a presigned object-store PUT (S3 mode); the bearer is
  * sent only to the control plane. Uploads run with bounded concurrency and bounded retries. Resume
  * is a property of the protocol: rerunning the same bundle negotiates a fresh session whose
  * `missing` list excludes every object the server already holds for this workspace, so an
  * interrupted push never retransmits completed objects.
  */
object Push:
  val Concurrency = 4
  val Attempts = 3

  def run(
      dir: Path,
      api: Api,
      ws: String,
      project: String,
      parent: Option[String],
      message: Option[String],
      token: String,
      out: String => IO[Unit] = IO.println
  ): IO[ExitCode] =
    val flow =
      for
        manifestBytes <- Files[IO].readAll(dir / "manifest.json").compile.to(Array)
        parsed <- Manifest.parse(manifestBytes) match
          case Right(p) => IO.pure(p)
          case Left(problems) =>
            problems.traverse_(p => out(s"error       ${p.render}")) *>
              IO.raiseError(CliError(s"manifest rejected: ${problems.length} problem(s)"))
        (digest, manifest) = parsed
        _ <- out(
          f"validating  manifest.json  ok  core ${manifest.core}  ${manifest.assets.length} assets"
        )
        files <- Files[IO].walk(dir / "assets")
          .filter(p => !p.toString.split('/').exists(_.startsWith(".")))
          .evalFilter(p => Files[IO].isRegularFile(p, false)).compile.toList
        hashed <- files.traverse(p =>
          Files[IO].readAll(p).compile.to(Array).map(b => Sha256.of(b).render -> (p, b))
        )
        byDigest = hashed.toMap
        resolved <- manifest.assets.traverse { a =>
          IO.fromOption(byDigest.get(a.digest.render))(CliError(
            s"asset ${a.id} ${a.digest.render} not found under ${dir / "assets"}"
          )).map(a -> _)
        }
        _ <- out(f"hashing     ${resolved.length} assets  ok")
        inv = manifest.assets.map(a => AssetInventory(a.digest.render, a.size, a.mediaType))
        created <- api.orFail(api.secured(
          Protocol.createUploadSession,
          token,
          (
            ws,
            project,
            CreateUploadSession(digest.render, manifestBytes.length.toLong, parent, inv)
          )
        ))
        _ <- out(
          s"negotiating upload session ${created.sessionId}  ${created.missing.length} of ${inv.length} objects missing"
        )
        _ <- created.missing.parTraverseN(Concurrency) { m =>
          val (_, (p, bytes)) = resolved.find(_._1.digest.render == m.digest).get
          upload(api, token, m, bytes, p.fileName.toString, out)
        }
        _ <- upload(
          api,
          token,
          UploadInstruction(digest.render, created.manifestUrl, "PUT", Map.empty),
          manifestBytes,
          "manifest.json",
          out
        )
        result <- api.secured(Protocol.commit, token, (created.sessionId, CommitRequest(message)))
        code <- result match
          case Right(c) =>
            out(s"committing  parent ${c.parent.getOrElse("(none)")} -> ${c.revisionId}  ok") *>
              out(s"digest      ${c.digest}") *>
              out(s"revision    ${c.revisionUrl}") *>
              out(s"view        ${c.viewUrl}").as(ExitCode.Success)
          case Left(e) if e.code == "stale_parent" =>
            out(
              s"rejected    ${e.message}; current head is ${e.head.getOrElse("(none)")}. Re-run with --parent ${e.head.getOrElse("")}"
            ).as(ExitCode.Error)
          case Left(e) =>
            out(s"error       ${e.code}: ${e.message}") *>
              e.problems.getOrElse(Nil).traverse_(p =>
                out(s"error       ${
                    if p.pointer.isEmpty then p.message else s"${p.pointer}: ${p.message}"
                  }")
              ).as(ExitCode.Error)
      yield code
    flow.handleErrorWith(e => out(s"error       ${Api.describe(api.server)(e)}").as(ExitCode.Error))

  /** One object, per its instruction, up to [[Attempts]] tries with back off. */
  def upload(
      api: Api,
      token: String,
      m: UploadInstruction,
      bytes: Array[Byte],
      name: String,
      out: String => IO[Unit]
  ): IO[Unit] =
    val uri = IO.fromEither(Uri.fromString(m.url).leftMap(_ =>
      CliError(s"upload $name: server returned an invalid URL ${m.url}")
    ))
    val method = Method.fromString(m.method).getOrElse(Method.PUT)
    // the bearer belongs to the control plane only, never to a signed object-store URL
    val toControlPlane = m.url.startsWith(api.server.stripSuffix("/") + "/")
    def attempt(n: Int): IO[Unit] =
      uri.flatMap { u =>
        val base = Request[IO](method, u).withEntity(bytes)
        val withAuth =
          if toControlPlane then
            base.putHeaders(Authorization(org.http4s.Credentials.Token(
              org.http4s.AuthScheme.Bearer,
              token
            )))
          else base
        val req = withAuth.putHeaders(
          Headers(m.headers.toList.map((k, v) => Header.Raw(CIString(k), v)))
        )
        api.raw.run(req).use { r =>
          if r.status.isSuccess then IO.unit
          else
            r.bodyText.compile.string.flatMap(b =>
              IO.raiseError(CliError(
                s"upload $name: HTTP ${r.status.code}${
                    io.circe.parser.decode[ApiError](b).toOption.fold("")(e => s" ${e.message}")
                  }"
              ))
            )
        }
      }.attempt.flatMap {
        case Right(()) =>
          out(s"uploading   $name  ${bytes.length} bytes  ok${
              if n > 1 then s"  (attempt $n)" else ""
            }")
        case Left(e) if n < Attempts =>
          out(s"uploading   $name  retry ${n + 1}/$Attempts: ${Api.describe(api.server)(e)}") *>
            IO.sleep(200.millis * (1L << (n - 1))) *> attempt(n + 1)
        case Left(e) =>
          IO.raiseError(CliError(s"${Api.describe(api.server)(e)} (after $Attempts attempts)"))
      }
    attempt(1)
