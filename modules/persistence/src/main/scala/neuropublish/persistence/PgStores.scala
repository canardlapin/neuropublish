package neuropublish.persistence

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import doobie.Transactor
import neuropublish.protocol.Sha256

/** Every PostgreSQL-backed store over one connection pool. [[PgStores.resource]] migrates first. */
final class PgStores(val xa: Transactor[IO]):
  val revisions: PgRevisionStore = PgRevisionStore(xa)
  val identity: PgIdentity = PgIdentity(xa)
  val members: PgMembers = PgMembers(xa)
  val sessions: PgSessions = PgSessions(xa)
  val tokens: PgUserTokens = PgUserTokens(xa)
  val credentials: PgCredentials = PgCredentials(xa)
  val views: PgViews = PgViews(xa)
  val links: PgShareLinks = PgShareLinks(xa)
  val audit: PgAudit = PgAudit(xa)
  val jobs: IngestionJobs.Service = IngestionJobs.Service(xa)

  /** `manifestBytes` fetches a manifest from the object store by digest. */
  def reindex(manifestBytes: Sha256 => IO[Option[Array[Byte]]]): Reindex =
    Reindex(xa, manifestBytes)

object PgStores:
  def resource(cfg: DbConfig, migrate: Boolean = true): Resource[IO, PgStores] =
    Resource.eval(if migrate then Database.migrate(cfg).void else IO.unit) *>
      Database.transactor(cfg).map(new PgStores(_))
