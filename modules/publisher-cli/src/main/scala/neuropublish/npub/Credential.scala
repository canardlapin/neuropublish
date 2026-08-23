package neuropublish.npub

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import neuropublish.api.*

/** `npub credential create|list|revoke`: project-scoped publisher credentials for batch jobs. */
object Credential:
  def create(
      api: Api,
      token: String,
      ws: String,
      project: String,
      name: String,
      out: String => IO[Unit]
  ): IO[ExitCode] =
    api.orFail(api.secured(Stage4.createCredential, token, (ws, project, CreateCredential(name))))
      .flatMap { c =>
        out(s"Created credential ${c.name} (${c.id}) for ${c.project}") *>
          out("") *>
          out(s"  NP_TOKEN=${c.secret}") *>
          out("") *>
          out(
            "This secret is shown once and is not stored anywhere else; put it in your job's secret store now."
          ).as(ExitCode.Success)
      }.handleErrorWith(fail(api, out))

  def list(
      api: Api,
      token: String,
      ws: String,
      project: String,
      out: String => IO[Unit]
  ): IO[ExitCode] =
    api.orFail(api.secured(Stage4.listCredentials, token, (ws, project))).flatMap { cs =>
      if cs.isEmpty then out(s"no credentials for $ws/$project").as(ExitCode.Success)
      else
        cs.traverse_(c => out(s"${c.id}  ${c.name}  created ${c.createdAt} by ${c.createdBy}"))
          .as(ExitCode.Success)
    }.handleErrorWith(fail(api, out))

  def revoke(
      api: Api,
      token: String,
      ws: String,
      project: String,
      id: String,
      out: String => IO[Unit]
  ): IO[ExitCode] =
    api.orFail(api.secured(Stage4.revokeCredential, token, (ws, project, id)))
      .flatMap(_ => out(s"Revoked credential $id").as(ExitCode.Success))
      .handleErrorWith(fail(api, out))

  private def fail(api: Api, out: String => IO[Unit])(e: Throwable): IO[ExitCode] =
    out(s"error  ${Api.describe(api.server)(e)}").as(ExitCode.Error)
