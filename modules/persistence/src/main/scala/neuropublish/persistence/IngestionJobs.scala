package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.time.Instant
import neuropublish.backend.{IngestionJob, IngestionQueue}

/** The ingestion worker's queue over `ingestion_jobs`. Status machine:
  * `pending → running → ready | failed`, and `running → pending` on a retryable failure. A job is
  * enqueued in the commit transaction ([[PgRevisionStore.commit]]); claims use
  * `FOR UPDATE SKIP LOCKED` so several workers never take the same job, and a `running` job whose
  * `locked_at` is older than [[IngestionQueue.Lease]] is due again (its worker died).
  */
object IngestionJobs:
  final case class Row(
      workspace: String,
      revisionId: String,
      status: String,
      attempts: Int,
      error: Option[String],
      updatedAt: Instant,
      availableAt: Instant
  ):
    def job: IngestionJob =
      IngestionJob(
        workspace,
        revisionId,
        status,
        attempts,
        error,
        Db.render(updatedAt),
        Option.when(status == "pending")(Db.render(availableAt))
      )

  private val columns =
    fr"workspace_id, revision_id, status, attempts, error, updated_at, available_at"

  /** Called inside the commit transaction; a second enqueue for the same revision resets it. */
  def enqueue(workspace: String, revisionId: String): ConnectionIO[Unit] =
    sql"""INSERT INTO ingestion_jobs (workspace_id, revision_id) VALUES ($workspace, $revisionId)
          ON CONFLICT (revision_id) DO UPDATE SET status = 'pending', attempts = 0, error = NULL,
            locked_by = NULL, locked_at = NULL, available_at = now(), updated_at = now()"""
      .update.run.void

  /** Take the first due job, marking it running for `claimant`; `None` when nothing is due. */
  def claim(now: Instant, claimant: String): ConnectionIO[Option[IngestionJob]] =
    val leaseEdge = now.minus(IngestionQueue.Lease)
    (fr"""UPDATE ingestion_jobs
          SET status = 'running', attempts = attempts + 1, locked_by = $claimant, locked_at = $now,
              updated_at = $now
          WHERE id = (SELECT id FROM ingestion_jobs
                      WHERE (status = 'pending' AND available_at <= $now)
                         OR (status = 'running' AND locked_at < $leaseEdge)
                      ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED)
          RETURNING""" ++ columns).query[Row].option.map(_.map(_.job))

  def succeed(revisionId: String): ConnectionIO[Unit] =
    sql"""UPDATE ingestion_jobs SET status = 'ready', error = NULL, locked_by = NULL,
          locked_at = NULL, updated_at = now() WHERE revision_id = $revisionId""".update.run.void

  /** Retry after the back off while attempts remain; otherwise terminal. */
  def fail(revisionId: String, error: String, now: Instant): ConnectionIO[Option[IngestionJob]] =
    sql"SELECT attempts FROM ingestion_jobs WHERE revision_id = $revisionId FOR UPDATE"
      .query[Int].option.flatMap {
        case None => FC.pure(Option.empty[IngestionJob])
        case Some(attempts) =>
          val retry = IngestionQueue.retries(attempts)
          val status = if retry then "pending" else "failed"
          val available = if retry then now.plus(IngestionQueue.backoff(attempts)) else now
          (fr"""UPDATE ingestion_jobs SET status = $status, error = $error, locked_by = NULL,
                locked_at = NULL, available_at = $available, updated_at = $now
                WHERE revision_id = $revisionId RETURNING""" ++ columns).query[Row].option
            .map(_.map(_.job))
      }

  def forRevision(revisionId: String): ConnectionIO[Option[IngestionJob]] =
    (fr"SELECT" ++ columns ++ fr"FROM ingestion_jobs WHERE revision_id = $revisionId")
      .query[Row].option.map(_.map(_.job))

  def all: ConnectionIO[List[IngestionJob]] =
    (fr"SELECT" ++ columns ++ fr"FROM ingestion_jobs ORDER BY created_at, id")
      .query[Row].to[List].map(_.map(_.job))

  /** Worker-side record of one produced representation (keys point into the object store). */
  def recordRepresentation(
      workspace: String,
      revisionId: String,
      assetId: String,
      kind: String,
      status: String,
      headerKey: Option[String],
      payloadKey: Option[String],
      error: Option[String]
  ): ConnectionIO[Unit] =
    sql"""INSERT INTO derived_representations
          (workspace_id, revision_id, asset_id, kind, status, header_key, payload_key, error, updated_at)
          VALUES ($workspace, $revisionId, $assetId, $kind, $status, $headerKey, $payloadKey, $error, now())
          ON CONFLICT (revision_id, asset_id, kind) DO UPDATE SET
            status = EXCLUDED.status, header_key = EXCLUDED.header_key,
            payload_key = EXCLUDED.payload_key, error = EXCLUDED.error, updated_at = now()"""
      .update.run.void

/** [[IngestionQueue]] over `ingestion_jobs`. `enqueue` outside a commit transaction exists for
  * operators re-running ingestion; the commit path enqueues inside its own transaction.
  */
final class PgIngestionQueue(xa: Transactor[IO]) extends IngestionQueue:
  def enqueue(workspace: String, revisionId: String): IO[Unit] =
    IngestionJobs.enqueue(workspace, revisionId).transact(xa)
  def claim(now: Instant, claimant: String): IO[Option[IngestionJob]] =
    IngestionJobs.claim(now, claimant).transact(xa)
  def complete(revisionId: String): IO[Unit] = IngestionJobs.succeed(revisionId).transact(xa)
  def fail(revisionId: String, error: String, now: Instant): IO[Option[IngestionJob]] =
    IngestionJobs.fail(revisionId, error, now).transact(xa)
  def status(revisionId: String): IO[Option[IngestionJob]] =
    IngestionJobs.forRevision(revisionId).transact(xa)
  def list: IO[List[IngestionJob]] = IngestionJobs.all.transact(xa)
