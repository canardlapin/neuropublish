package neuropublish.backend

import cats.effect.IO
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import neuropublish.api.IngestionStatus

/** One ingestion job per committed revision. `status` is pending | running | ready | failed;
  * `attempts` counts claims; `nextAttemptAt` is the retry back off of a pending job; `error` is the
  * last failure message.
  */
final case class IngestionJob(
    workspace: String,
    revisionId: String,
    status: String,
    attempts: Int,
    error: Option[String],
    updatedAt: String,
    nextAttemptAt: Option[String]
):
  def api: IngestionStatus = IngestionStatus(status, updatedAt, error, attempts)
object IngestionJob:
  given Codec[IngestionJob] = deriveCodec

/** The ingestion work queue shared by the control plane (producer) and the worker (consumer).
  * Producers enqueue inside the revision commit ([[RevisionStore.commit]]); consumers claim with a
  * lease: a job still `running` after [[IngestionQueue.Lease]] belongs to a dead worker and may be
  * claimed again. Implementations: `IngestionQueue.LocalFs` (job files) and
  * `neuropublish.persistence.PgIngestionQueue` (`ingestion_jobs`, `FOR UPDATE SKIP LOCKED`).
  */
trait IngestionQueue:
  /** Record a pending job for `revisionId` (idempotent; re-enqueueing a finished job resets it). */
  def enqueue(workspace: String, revisionId: String): IO[Unit]

  /** Atomically take one due job — pending with its back off elapsed, or running past the lease —
    * mark it running for `claimant`, and count the attempt.
    */
  def claim(now: Instant, claimant: String): IO[Option[IngestionJob]]

  /** The worker finished deriving every rendition. */
  def complete(revisionId: String): IO[Unit]

  /** An attempt failed: pending again at `now + backoff(attempts)` while attempts <
    * [[IngestionQueue.MaxAttempts]], else terminally `failed`. Returns the job as recorded.
    */
  def fail(revisionId: String, error: String, now: Instant): IO[Option[IngestionJob]]
  def status(revisionId: String): IO[Option[IngestionJob]]

  /** Every job (operator listing and tests). */
  def list: IO[List[IngestionJob]]

object IngestionQueue:
  val MaxAttempts = 3

  /** How long a claim holds a job before another worker may take it over. */
  val Lease: java.time.Duration = java.time.Duration.ofMinutes(10)

  /** Exponential back off after a failed attempt: 2 s, 4 s, … (attempt 1 → 2 s). */
  def backoff(attempt: Int): java.time.Duration = java.time.Duration.ofSeconds(1L << attempt)

  /** Whether a failed attempt number `attempts` is retried. */
  def retries(attempts: Int): Boolean = attempts < MaxAttempts
