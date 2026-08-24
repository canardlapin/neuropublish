package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import io.circe.syntax.*
import java.time.Instant
import neuropublish.api.Membership
import neuropublish.backend.{Identity, MemberRecord, Members, Role, Secrets, UserRecord}

/** `users` + `identities(issuer, subject)`. The local provider's password hash is a `jsonb` column;
  * an external IdP adds `identities` rows and leaves it null.
  */
final class PgIdentity(xa: Transactor[IO]) extends Identity:
  import PgIdentity.*

  def lookup(userId: String): IO[Option[UserRecord]] =
    (selectUser ++ fr"WHERE u.id = $userId").query[Row].option.map(_.map(_.record)).transact(xa)

  def lookupIdentity(issuer: String, subject: String): IO[Option[UserRecord]] =
    (selectUser ++
      fr"""JOIN identities i ON i.user_id = u.id
           WHERE i.issuer = $issuer AND i.subject = ${subject.toLowerCase}""")
      .query[Row].option.map(_.map(_.record)).transact(xa)

  def authenticate(email: String, password: String): IO[Option[UserRecord]] =
    Identity.authenticateLocal(lookupIdentity)(email, password)

  def ensureLocalUser(email: String, name: String, password: String): IO[UserRecord] =
    for
      id <- Identity.newId
      hash <- Secrets.hashPassword(password)
      now <- IO.realTimeInstant
      subject = email.toLowerCase
      tx =
        for
          // serialize creation per (issuer, subject) so two concurrent calls agree on one user
          _ <- sql"SELECT pg_advisory_xact_lock(hashtext(${Identity.LocalIssuer + ":" + subject}))"
            .query[Unit].unique
          existing <-
          (selectUser ++
            fr"""JOIN identities i ON i.user_id = u.id
               WHERE i.issuer = ${Identity.LocalIssuer} AND i.subject = $subject""")
            .query[Row].option
          u <- existing match
            case Some(r) => FC.pure(r.record)
            case None =>
              val rec = UserRecord(id, email, name, Some(hash), Db.render(now))
              sql"""INSERT INTO users (id, email, name, password_hash, created_at)
                  VALUES ($id, $email, $name, ${hash.asJson}, $now)""".update.run *>
                sql"""INSERT INTO identities (issuer, subject, user_id)
                    VALUES (${Identity.LocalIssuer}, $subject, $id)""".update.run.as(rec)
        yield u
      u <- tx.transact(xa)
    yield u

  def changeLocalPassword(email: String, password: String): IO[Option[UserRecord]] =
    for
      hash <- Secrets.hashPassword(password)
      subject = email.toLowerCase
      changed <- (for
        id <- sql"""SELECT user_id FROM identities
                    WHERE issuer = ${Identity.LocalIssuer} AND subject = $subject"""
          .query[String].option
        _ <- id.traverse_(userId =>
          sql"UPDATE users SET password_hash = ${hash.asJson} WHERE id = $userId".update.run.void
        )
      yield id).transact(xa)
      user <- changed.traverse(lookup)
    yield user.flatten

object PgIdentity:
  final case class Row(
      id: String,
      email: String,
      name: String,
      password: Option[Json],
      createdAt: Instant
  ):
    def record: UserRecord =
      UserRecord(
        id,
        email,
        name,
        password.flatMap(_.as[Secrets.PasswordHash].toOption),
        Db.render(createdAt)
      )
  val selectUser: Fragment =
    fr"SELECT u.id, u.email, u.name, u.password_hash, u.created_at FROM users u"

/** `workspace_members`. */
final class PgMembers(xa: Transactor[IO]) extends Members:
  import Db.given

  def role(ws: String, userId: String): IO[Option[Role]] =
    sql"SELECT role FROM workspace_members WHERE workspace_id = $ws AND user_id = $userId"
      .query[Role].option.transact(xa)

  def members(ws: String): IO[List[MemberRecord]] =
    sql"""SELECT user_id, role, added_at FROM workspace_members
          WHERE workspace_id = $ws ORDER BY added_at, user_id"""
      .query[(String, Role, Instant)].to[List]
      .map(_.map((u, r, at) => MemberRecord(u, r, Db.render(at)))).transact(xa)

  def membershipsOf(userId: String): IO[List[Membership]] =
    sql"""SELECT workspace_id, role FROM workspace_members
          WHERE user_id = $userId ORDER BY workspace_id"""
      .query[(String, Role)].to[List].map(_.map((w, r) => Membership(w, r.render))).transact(xa)

  def set(ws: String, userId: String, role: Role): IO[Unit] =
    IO.realTimeInstant.flatMap { now =>
      sql"""INSERT INTO workspace_members (workspace_id, user_id, role, added_at)
            VALUES ($ws, $userId, $role, $now)
            ON CONFLICT (workspace_id, user_id) DO UPDATE SET role = EXCLUDED.role, added_at = EXCLUDED.added_at"""
        .update.run.void.transact(xa)
    }
