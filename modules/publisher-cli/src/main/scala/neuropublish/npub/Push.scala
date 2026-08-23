package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import neuropublish.api.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest

object Push:
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
        parsed <- IO.fromEither(Manifest.parse(manifestBytes).leftMap(m =>
          CliError(s"manifest rejected: $m")
        ))
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
        _ <- created.missing.traverse_ { m =>
          val (_, (p, bytes)) = resolved.find(_._1.digest.render == m.digest).get
          api.secured(Protocol.uploadObject, token, (created.sessionId, m.digest, bytes))
            .flatMap(r =>
              IO.fromEither(r.leftMap(e => CliError(s"upload ${p.fileName}: ${e.message}")))
            ) *>
            out(s"uploading   ${p.fileName}  ${bytes.length} bytes  ok")
        }
        _ <- api.secured(Protocol.uploadManifest, token, (created.sessionId, manifestBytes))
          .flatMap(r => IO.fromEither(r.leftMap(e => CliError(e.message))))
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
          case Left(e) => out(s"error       ${e.code}: ${e.message}").as(ExitCode.Error)
      yield code
    flow.handleErrorWith(e => out(s"error       ${Api.describe(api.server)(e)}").as(ExitCode.Error))
