package neuropublish.persistence

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import java.time.Instant
import neuropublish.backend.Role
import org.flywaydb.core.Flyway

/** `NP_DATABASE_URL` (+ `NP_DATABASE_USER` / `NP_DATABASE_PASSWORD`): a JDBC PostgreSQL URL. */
final case class DbConfig(url: String, user: String, password: String, poolSize: Int = 8)

object DbConfig:
  def fromEnv(env: Map[String, String]): Option[DbConfig] =
    env.get("NP_DATABASE_URL").map(_.trim).filter(_.nonEmpty).map(url =>
      DbConfig(
        url,
        env.getOrElse("NP_DATABASE_USER", "neuropublish"),
        env.getOrElse("NP_DATABASE_PASSWORD", ""),
        env.get("NP_DATABASE_POOL").flatMap(_.toIntOption).getOrElse(8)
      )
    )

object Database:
  /** Apply every pending migration under `db/migration`; returns how many ran. */
  def migrate(cfg: DbConfig): IO[Int] =
    IO.blocking {
      Flyway.configure()
        .dataSource(cfg.url, cfg.user, cfg.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
        .migrationsExecuted
    }

  def transactor(cfg: DbConfig): Resource[IO, Transactor[IO]] =
    for
      ec <- ExecutionContexts.fixedThreadPool[IO](cfg.poolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        cfg.url,
        cfg.user,
        cfg.password,
        ec
      )
    yield xa

/** Column codecs shared by the stores (each store also imports `doobie.postgres.implicits.*` for
  * `Instant`/`UUID` and `doobie.postgres.circe.jsonb.implicits.*` for `jsonb`). Timestamps are
  * `timestamptz`; the domain records carry them as ISO-8601 strings (`Instant.toString`), which is
  * what the JSON API renders.
  */
object Db:
  given Meta[Role] = Meta[String].timap(s =>
    Role.parse(s).getOrElse(throw IllegalArgumentException(s"unknown role $s"))
  )(_.render)

  def ts(s: String): Instant = Instant.parse(s)
  def render(i: Instant): String = i.toString
  def renderOpt(i: Option[Instant]): Option[String] = i.map(_.toString)

  /** The workspace-scoped project id subquery used by every child insert: a missing project makes
    * the insert fail on NOT NULL rather than silently attaching to another workspace.
    */
  def projectId(workspace: String, slug: String): Fragment =
    fr"(SELECT id FROM projects WHERE workspace_id = $workspace AND slug = $slug)"
