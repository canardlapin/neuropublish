package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.time.Instant
import neuropublish.backend.{
  CredentialRecord,
  Credentials,
  ProjectKey,
  Secrets,
  Sessions,
  UserTokenRecord,
  UserTokens
}

/** `sessions`: the cookie secret is hashed; an expired row is deleted when met. */
final class PgSessions(xa: Transactor[IO]) extends Sessions:
  def create(userId: String): IO[(String, Instant)] =
    for
      secret <- Secrets.token(32)
      now <- IO.realTimeInstant
      exp = now.plusMillis(lifetime.toMillis)
      _ <- sql"""INSERT INTO sessions (secret_hash, user_id, created_at, expires_at)
                 VALUES (${Secrets.sha256Hex(secret)}, $userId, $now, $exp)""".update.run
        .transact(xa)
    yield (secret, exp)

  def resolve(secret: String): IO[Option[String]] =
    val hash = Secrets.sha256Hex(secret)
    IO.realTimeInstant.flatMap { now =>
      (sql"SELECT user_id, expires_at FROM sessions WHERE secret_hash = $hash"
        .query[(String, Instant)].option.flatMap {
          case Some((uid, exp)) if exp.isAfter(now) => FC.pure(Option(uid))
          case Some(_) =>
            sql"DELETE FROM sessions WHERE secret_hash = $hash".update.run.as(Option.empty[String])
          case None => FC.pure(Option.empty[String])
        }).transact(xa)
    }

  def revoke(secret: String): IO[Unit] =
    sql"DELETE FROM sessions WHERE secret_hash = ${Secrets.sha256Hex(secret)}".update.run.void
      .transact(xa)

/** `user_tokens`: device-flow bearers, hashed; expired rows are deleted when met. */
final class PgUserTokens(xa: Transactor[IO]) extends UserTokens:
  def mint(userId: String, client: String): IO[String] =
    for
      secret <- Secrets.token(32).map(UserTokens.Prefix + _)
      now <- IO.realTimeInstant
      exp = now.plusMillis(lifetime.toMillis)
      _ <- sql"""INSERT INTO user_tokens (secret_hash, user_id, client, created_at, expires_at)
                 VALUES (${Secrets.sha256Hex(secret)}, $userId, $client, $now, $exp)""".update.run
        .transact(xa)
    yield secret

  def resolve(secret: String): IO[Option[UserTokenRecord]] =
    val hash = Secrets.sha256Hex(secret)
    IO.realTimeInstant.flatMap { now =>
      (sql"SELECT user_id, client, created_at, expires_at FROM user_tokens WHERE secret_hash = $hash"
        .query[(String, String, Instant, Instant)].option.flatMap {
          case Some((uid, client, created, exp)) if exp.isAfter(now) =>
            FC.pure(Option(UserTokenRecord(uid, client, Db.render(created), Some(Db.render(exp)))))
          case Some(_) =>
            sql"DELETE FROM user_tokens WHERE secret_hash = $hash".update.run
              .as(Option.empty[UserTokenRecord])
          case None => FC.pure(Option.empty[UserTokenRecord])
        }).transact(xa)
    }

  def revoke(secret: String): IO[Unit] =
    sql"DELETE FROM user_tokens WHERE secret_hash = ${Secrets.sha256Hex(secret)}".update.run.void
      .transact(xa)

  def revokeAll(userId: String): IO[Int] =
    sql"DELETE FROM user_tokens WHERE user_id = $userId".update.run.transact(xa)

/** `publisher_credentials`: project-scoped non-human principals (ADR 0004). */
final class PgCredentials(xa: Transactor[IO]) extends Credentials:
  import PgCredentials.*

  def create(key: ProjectKey, name: String, createdBy: String): IO[(CredentialRecord, String)] =
    for
      id <- Credentials.newId
      secret <- Credentials.newSecret
      now <- IO.realTimeInstant
      hash = Secrets.sha256Hex(secret)
      _ <-
      (fr"""INSERT INTO publisher_credentials
                 (id, workspace_id, project_id, name, secret_hash, created_at, created_by)
                 VALUES ($id, ${key.workspace},""" ++ Db.projectId(key.workspace, key.project) ++
        fr", $name, $hash, $now, $createdBy)").update.run.transact(xa)
    yield (
      CredentialRecord(
        id,
        name,
        key.workspace,
        key.project,
        hash,
        Db.render(now),
        createdBy,
        None
      ),
      secret
    )

  private def byId(id: String): IO[Option[CredentialRecord]] =
    (select ++ fr"WHERE c.id = $id").query[Row].option.map(_.map(_.record)).transact(xa)
  def get(workspace: String, id: String): IO[Option[CredentialRecord]] =
    (select ++ fr"WHERE c.workspace_id = $workspace AND c.id = $id").query[Row].option
      .map(_.map(_.record)).transact(xa)

  def resolveSecret(secret: String): IO[Option[CredentialRecord]] =
    (select ++ fr"WHERE c.secret_hash = ${Secrets.sha256Hex(secret)}")
      .query[Row].option.map(_.map(_.record)).transact(xa)

  def list(key: ProjectKey): IO[List[CredentialRecord]] =
    (select ++
      fr"""WHERE c.workspace_id = ${key.workspace} AND p.slug = ${key.project}
           AND c.revoked_at IS NULL ORDER BY c.created_at, c.id""")
      .query[Row].to[List].map(_.map(_.record)).transact(xa)

  def revoke(id: String): IO[Option[CredentialRecord]] =
    IO.realTimeInstant.flatMap { now =>
      sql"""UPDATE publisher_credentials SET revoked_at = $now
            WHERE id = $id AND revoked_at IS NULL""".update.run.transact(xa)
    } *> byId(id)

object PgCredentials:
  final case class Row(
      id: String,
      name: String,
      workspace: String,
      project: String,
      secretHash: String,
      createdAt: Instant,
      createdBy: String,
      revokedAt: Option[Instant]
  ):
    def record: CredentialRecord =
      CredentialRecord(
        id,
        name,
        workspace,
        project,
        secretHash,
        Db.render(createdAt),
        createdBy,
        Db.renderOpt(revokedAt)
      )
  val select: Fragment =
    fr"""SELECT c.id, c.name, c.workspace_id, p.slug, c.secret_hash, c.created_at, c.created_by,
                c.revoked_at
         FROM publisher_credentials c
         JOIN projects p ON p.workspace_id = c.workspace_id AND p.id = c.project_id"""
