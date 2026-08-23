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
import org.http4s.headers.{Authorization, `Content-Length`}
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

/** `npub push`: hash, negotiate, upload what is missing, commit.
  *
  * Each missing object follows its [[UploadInstruction]] verbatim (method, URL, headers), whether
  * that is the control plane (local mode) or a presigned object-store PUT (S3 mode); the bearer is
  * sent only to URLs on the control plane's origin (scheme, host, port). Files are hashed and
  * uploaded as streams with an explicit `Content-Length`, so a bundle of any size fits in memory.
  * Uploads run with bounded concurrency and bounded retries. Resume is a property of the protocol:
  * rerunning the same bundle negotiates a fresh session whose `missing` list excludes every object
  * the server already holds for this workspace, so an interrupted push never retransmits completed
  * objects. Re-pushing a bundle that is already the project head is not an error: the CLI reports
  * the head and exits 0.
  */
object Push:
  val Concurrency = 4
  val Attempts = 3

  /** A file in the bundle's `assets/` tree, hashed once. */
  final case class Local(path: Path, digest: Sha256, size: Long)

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
        _ <- insecureWarning(api.server).traverse_(out)
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
        hashed <- files.traverse(p => Pack.hashFile(p).map((d, n) => Local(p, d, n)))
        byDigest = hashed.map(l => l.digest.hex -> l).toMap
        resolved <- manifest.assets.traverse { a =>
          IO.fromOption(byDigest.get(a.digest.hex))(CliError(
            s"asset ${a.id} ${a.digest.render} not found under ${dir / "assets"}"
          )).flatMap { l =>
            // a declared size that disagrees with the file is refused before any negotiation
            if l.size != a.size then
              IO.raiseError(CliError(
                s"asset ${a.id}: manifest declares ${a.size} B but ${l.path.fileName} is ${l.size} B"
              ))
            else IO.pure(a -> l)
          }
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
          val local = Sha256.parse(m.digest).toOption.flatMap(d => byDigest.get(d.hex))
          IO.fromOption(local)(CliError(
            s"server asked for ${m.digest}, which is not in the bundle"
          )).flatMap(l =>
            upload(api, token, m, Files[IO].readAll(l.path), l.size, l.path.fileName.toString, out)
          )
        }
        _ <- upload(
          api,
          token,
          UploadInstruction(digest.render, created.manifestUrl, "PUT", Map.empty),
          fs2.Stream.emits(manifestBytes),
          manifestBytes.length.toLong,
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
            alreadyPublished(api, token, e.head, digest).flatMap {
              case true =>
                out(s"unchanged   already published as ${e.head.getOrElse("")}")
                  .as(ExitCode.Success)
              case false =>
                out(
                  s"rejected    ${e.message}; current head is ${e.head.getOrElse("(none)")}. Re-run with --parent ${e.head.getOrElse("")}"
                ).as(ExitCode.Error)
            }
          case Left(e) =>
            out(s"error       ${e.code}: ${e.message}") *>
              e.problems.getOrElse(Nil).traverse_(p =>
                out(s"error       ${
                    if p.pointer.isEmpty then p.message else s"${p.pointer}: ${p.message}"
                  }")
              ).as(ExitCode.Error)
      yield code
    flow.handleErrorWith(e => out(s"error       ${Api.describe(api.server)(e)}").as(ExitCode.Error))

  /** A stale-parent rejection whose head already holds these bytes is an idempotent re-push. */
  private def alreadyPublished(
      api: Api,
      token: String,
      head: Option[String],
      digest: Sha256
  ): IO[Boolean] =
    head match
      case None => IO.pure(false)
      case Some(id) =>
        api.secured(Protocol.revision, token, id).attempt.map {
          case Right(Right(d)) => Sha256.parse(d.digest).toOption.exists(_.hex == digest.hex)
          case _ => false
        }

  /** Plain `http` to anything but a loopback host sends the bearer in clear. */
  def insecureWarning(server: String): Option[String] =
    Origin.parse(server).collect {
      case o if o.scheme == "http" && !o.isLoopback =>
        s"warning     $server is plain http to a non-loopback host; the token travels unencrypted"
    }

  private val Token = "^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$".r

  /** Server-issued headers are followed verbatim, but never past the HTTP grammar: a non-token name
    * or a CR/LF/NUL in a value would let a hostile response smuggle a second header.
    */
  def validateHeaders(headers: Map[String, String]): Either[String, Unit] =
    headers.toList.collectFirst {
      case (k, _) if !Token.matches(k) => s"invalid header name '$k'"
      case (k, v) if v.exists(c => (c < 0x20 && c != '\t') || c == 0x7f) =>
        s"header '$k' value contains a control character"
    }.toLeft(())

  /** One object, per its instruction, up to [[Attempts]] tries with back off. The body is a stream
    * re-run on every attempt; `size` is its exact length (`Content-Length`).
    */
  def upload(
      api: Api,
      token: String,
      m: UploadInstruction,
      body: fs2.Stream[IO, Byte],
      size: Long,
      name: String,
      out: String => IO[Unit]
  ): IO[Unit] =
    val uri = IO.fromEither(Uri.fromString(m.url).leftMap(_ =>
      CliError(s"upload $name: server returned an invalid URL ${m.url}")
    ))
    val method = Method.fromString(m.method).getOrElse(Method.PUT)
    // the bearer belongs to the control plane only, never to a signed object-store URL
    val toControlPlane = Origin.same(m.url, api.server)
    def attempt(n: Int): IO[Unit] =
      uri.flatMap { u =>
        IO.fromEither(validateHeaders(m.headers).leftMap(msg =>
          CliError(s"upload $name: $msg")
        )).flatMap { _ =>
          val base = Request[IO](method, u).withBodyStream(body)
            .putHeaders(`Content-Length`.unsafeFromLong(size))
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
        }
      }.attempt.flatMap {
        case Right(()) =>
          out(s"uploading   $name  $size bytes  ok${if n > 1 then s"  (attempt $n)" else ""}")
        case Left(CliError(msg))
            if msg.contains("invalid header") ||
              msg.contains("control character") =>
          IO.raiseError(CliError(msg))
        case Left(e) if n < Attempts =>
          out(s"uploading   $name  retry ${n + 1}/$Attempts: ${Api.describe(api.server)(e)}") *>
            IO.sleep(200.millis * (1L << (n - 1))) *> attempt(n + 1)
        case Left(e) =>
          IO.raiseError(CliError(s"${Api.describe(api.server)(e)} (after $Attempts attempts)"))
      }
    attempt(1)
