package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.time.Instant

/** The ingestion worker's queue over `ingestion_jobs`. Status machine:
  * `pending → running → ready | failed`, and `running → pending` on a retryable failure. A job is
  * enqueued in the commit transaction ([[PgRevisionStore.commit]]); claims use
  * `FOR UPDATE SKIP LOCKED` so several workers never take the same job.
  */
object IngestionJobs:
  enum Status(val render: String):
    case Pending extends Status("pending")
    case Running extends Status("running")
    case Ready extends Status("ready")
    case Failed extends Status("failed")
  object Status:
    def parse(s: String): Option[Status] = values.find(_.render == s)

  final case class Job(
      id: Long,
      revisionId: String,
      status: Status,
      attempts: Int,
      error: Option[String],
      updatedAt: Instant
  )

  given Meta[Status] = Meta[String].timap(s =>
    Status.parse(s).getOrElse(throw IllegalArgumentException(s"unknown job status $s"))
  )(_.render)

  /** Called inside the commit transaction; a second enqueue for the same revision is a no-op. */
  def enqueue(revisionId: String): ConnectionIO[Unit] =
    sql"INSERT INTO ingestion_jobs (revision_id) VALUES ($revisionId) ON CONFLICT DO NOTHING"
      .update.run.void

  /** Take the oldest pending job, marking it running for `worker`; `None` when the queue is empty.
    */
  def claim(worker: String): ConnectionIO[Option[Job]] =
    sql"""UPDATE ingestion_jobs
          SET status = 'running', attempts = attempts + 1, locked_by = $worker, updated_at = now()
          WHERE id = (SELECT id FROM ingestion_jobs WHERE status = 'pending'
                      ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
          RETURNING id, revision_id, status, attempts, error, updated_at"""
      .query[Job].option

  def succeed(id: Long): ConnectionIO[Unit] =
    sql"""UPDATE ingestion_jobs SET status = 'ready', error = NULL, locked_by = NULL,
          updated_at = now() WHERE id = $id""".update.run.void

  /** `retry = true` returns the job to the queue; otherwise it is terminal. */
  def fail(id: Long, error: String, retry: Boolean): ConnectionIO[Unit] =
    val status: Status = if retry then Status.Pending else Status.Failed
    sql"""UPDATE ingestion_jobs SET status = $status, error = $error, locked_by = NULL,
          updated_at = now() WHERE id = $id""".update.run.void

  def forRevision(revisionId: String): ConnectionIO[Option[Job]] =
    sql"""SELECT id, revision_id, status, attempts, error, updated_at FROM ingestion_jobs
          WHERE revision_id = $revisionId""".query[Job].option

  /** Worker-side record of one produced representation (keys point into the object store). */
  def recordRepresentation(
      revisionId: String,
      assetId: String,
      kind: String,
      status: Status,
      headerKey: Option[String],
      payloadKey: Option[String],
      error: Option[String]
  ): ConnectionIO[Unit] =
    sql"""INSERT INTO derived_representations
          (revision_id, asset_id, kind, status, header_key, payload_key, error, updated_at)
          VALUES ($revisionId, $assetId, $kind, $status, $headerKey, $payloadKey, $error, now())
          ON CONFLICT (revision_id, asset_id, kind) DO UPDATE SET
            status = EXCLUDED.status, header_key = EXCLUDED.header_key,
            payload_key = EXCLUDED.payload_key, error = EXCLUDED.error, updated_at = now()"""
      .update.run.void

  final class Service(xa: Transactor[IO]):
    def claim(worker: String): IO[Option[Job]] = IngestionJobs.claim(worker).transact(xa)
    def succeed(id: Long): IO[Unit] = IngestionJobs.succeed(id).transact(xa)
    def fail(id: Long, error: String, retry: Boolean): IO[Unit] =
      IngestionJobs.fail(id, error, retry).transact(xa)
    def forRevision(revisionId: String): IO[Option[Job]] =
      IngestionJobs.forRevision(revisionId).transact(xa)
