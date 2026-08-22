package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import neuropublish.api.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import sttp.tapir.client.http4s.Http4sClientInterpreter

object Push:
  def run(
      dir: Path,
      server: String,
      ws: String,
      project: String,
      parent: Option[String],
      message: Option[String],
      token: String
  ): IO[ExitCode] =
    val out = (s: String) => IO.println(s)
    EmberClientBuilder.default[IO].build.use { client =>
      val base = Some(Uri.unsafeFromString(server))
      val interp = Http4sClientInterpreter[IO]()
      def call[S, I, E, O](
          ep: sttp.tapir.Endpoint[S, I, E, O, Any],
          sec: S,
          in: I
      ): IO[Either[E, O]] =
        val (req, handle) = interp.toSecureRequestThrowDecodeFailures(ep, base).apply(sec).apply(in)
        client.run(req).use(handle)

      for
        manifestBytes <- Files[IO].readAll(dir / "manifest.json").compile.to(Array)
        parsed <- IO.fromEither(Manifest.parse(manifestBytes).leftMap(m =>
          PushError(s"manifest rejected: $m")
        ))
        (digest, manifest) = parsed
        _ <- out(
          f"validating  manifest.json  ok  core ${manifest.core}  ${manifest.assets.length} assets"
        )
        files <- Files[IO].walk(dir / "assets").filter(p =>
          !p.fileName.toString.startsWith(".")
        ).evalFilter(Files[IO].isRegularFile(_)).compile.toList
        hashed <- files.traverse(p =>
          Files[IO].readAll(p).compile.to(Array).map(b => Sha256.of(b).render -> (p, b))
        )
        byDigest = hashed.toMap
        resolved <- manifest.assets.traverse { a =>
          IO.fromOption(byDigest.get(a.digest.render))(PushError(
            s"asset ${a.id} ${a.digest.render} not found under ${dir / "assets"}"
          )).map(a -> _)
        }
        _ <- out(f"hashing     ${resolved.length} assets  ok")
        inv = manifest.assets.map(a => AssetInventory(a.digest.render, a.size, a.mediaType))
        created <- call(
          Protocol.createUploadSession,
          token,
          (
            ws,
            project,
            CreateUploadSession(digest.render, manifestBytes.length.toLong, parent, inv)
          )
        )
          .flatMap(r => IO.fromEither(r.leftMap(e => PushError(s"${e.code}: ${e.message}"))))
        _ <- out(
          s"negotiating upload session ${created.sessionId}  ${created.missing.length} of ${inv.length} objects missing"
        )
        _ <- created.missing.traverse_ { m =>
          val (_, (p, bytes)) = resolved.find(_._1.digest.render == m.digest).get
          call(Protocol.uploadObject, token, (created.sessionId, m.digest, bytes))
            .flatMap(r =>
              IO.fromEither(r.leftMap(e => PushError(s"upload ${p.fileName}: ${e.message}")))
            ) *>
            out(s"uploading   ${p.fileName}  ${bytes.length} bytes  ok")
        }
        _ <- call(Protocol.uploadManifest, token, (created.sessionId, manifestBytes)).flatMap(r =>
          IO.fromEither(r.leftMap(e => PushError(e.message)))
        )
        result <- call(Protocol.commit, token, (created.sessionId, CommitRequest(message)))
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
          case Left(e) => out(s"error       ${e.code}: ${e.message}").as(ExitCode.Error)
      yield code
    }.handleErrorWith {
      case PushError(m) => IO.println(s"error       $m").as(ExitCode.Error)
    }

final case class PushError(message: String) extends RuntimeException(message)
