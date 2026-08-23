package neuropublish.backend

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fs2.io.file.Files
import java.time.Instant
import neuropublish.persistence.PgTestDatabase

/** The same route suites over the PostgreSQL-backed [[Server.build]]. Each test gets a fresh
  * database (migrated by Flyway) in a Testcontainers PostgreSQL; without Docker the tests skip.
  */
object PgServerFactory extends ServerFactory:
  def build(
      bootstrap: ProjectKey,
      baseUrl: String,
      ownerEmail: String,
      ownerPassword: String,
      legacyToken: Option[String],
      extra: List[Server.Bootstrap]
  ): Resource[IO, TestServer] =
    for
      pg <- PgTestDatabase.fresh
      dir <- Files[IO].tempDirectory
      routes <- Resource.eval(Server.build(
        Server.Stores.fromPg(pg),
        dir,
        bootstrap,
        baseUrl,
        ownerEmail,
        ownerPassword,
        legacyToken,
        extra,
        None
      ))
    yield new TestServer:
      val app = routes.orNotFound

      /** Every row of every table that holds a principal or a secret, as JSON text. */
      def storedText: IO[String] =
        List(
          "users",
          "identities",
          "sessions",
          "user_tokens",
          "publisher_credentials",
          "share_links",
          "saved_views",
          "audit_events"
        ).traverse { t =>
          (fr"SELECT row_to_json(t)::text FROM" ++ doobie.Fragment.const(t) ++ fr"t")
            .query[String].to[List].transact(pg.xa)
        }.map(_.flatten.mkString("\n"))
      def tokenExpiry(secret: String): IO[Option[Instant]] =
        sql"SELECT expires_at FROM user_tokens WHERE secret_hash = ${Secrets.sha256Hex(secret)}"
          .query[Instant].option.transact(pg.xa)
      def setTokenExpiry(secret: String, at: Instant): IO[Unit] =
        sql"""UPDATE user_tokens SET expires_at = $at
              WHERE secret_hash = ${Secrets.sha256Hex(secret)}""".update.run.void.transact(pg.xa)

class PgRoutesSuite extends RoutesSpec(PgServerFactory)
class PgStage4Suite extends Stage4Spec(PgServerFactory)
