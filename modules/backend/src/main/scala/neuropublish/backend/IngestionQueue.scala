package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import neuropublish.api.IngestionStatus

/** One ingestion job per committed revision. `status` is pending | running | ready | failed;
  * `nextAttemptAt` holds a retry back off; `error` is set on the final failure.
  */
final case class IngestionJob(
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
  *
  * Adapter point for `modules/persistence`: the PostgreSQL implementation maps this trait onto the
  * `ingestion_jobs` table (status pending|running|ready|failed, attempts, error, updated_at), with
  * [[claim]] as `UPDATE … SET status='running' WHERE status='pending' AND next_attempt_at <= now …
  * FOR UPDATE SKIP LOCKED RETURNING *`. Wire it in [[neuropublish.backend.Server.stores]].
  */
trait IngestionQueue:
  /** Record a pending job for `revisionId` (idempotent; re-enqueueing a finished job resets it). */
  def enqueue(revisionId: String): IO[Unit]

  /** Atomically take one pending job whose back off has elapsed and mark it running. */
  def claim(now: Instant): IO[Option[IngestionJob]]

  /** The worker finished deriving every rendition. */
  def complete(revisionId: String): IO[Unit]

  /** An attempt failed: `retryAt` = Some → pending again at that time, None → terminal failure. */
  def fail(revisionId: String, error: String, retryAt: Option[Instant]): IO[Unit]
  def status(revisionId: String): IO[Option[IngestionJob]]

  /** Every job (operator listing and tests). */
  def list: IO[List[IngestionJob]]

object IngestionQueue:
  val MaxAttempts = 3

  /** Exponential back off after a failed attempt: 2 s, 4 s, … (attempt 1 → 2 s). */
  def backoff(attempt: Int): java.time.Duration = java.time.Duration.ofSeconds(1L << attempt)

  /** `<data>/queue/<rev>.json`, one file per job. Producer and consumer may be different processes:
    * a claim is the atomic creation of `<rev>.claim`, which the consumer removes when it records
    * the outcome, so two workers on one data dir never run the same job.
    */
  final class LocalFs(dir: Path) extends IngestionQueue:
    private def file(rev: String) = dir / s"$rev.json"
    private def claimFile(rev: String) = dir / s"$rev.claim"
    private def write(j: IngestionJob): IO[Unit] = JsonFiles.write(file(j.revisionId), j)
    private def now = IO.realTimeInstant.map(_.toString)

    def enqueue(rev: String): IO[Unit] =
      now.flatMap(t => write(IngestionJob(rev, "pending", 0, None, t, None)))

    def claim(at: Instant): IO[Option[IngestionJob]] =
      list.flatMap { jobs =>
        val due = jobs.filter(j =>
          j.status == "pending" && j.nextAttemptAt.forall(n => !Instant.parse(n).isAfter(at))
        ).sortBy(_.updatedAt)
        due.foldLeftM(Option.empty[IngestionJob]) {
          case (Some(taken), _) => IO.pure(Some(taken))
          case (None, j) =>
            // createFile is atomic: exactly one claimant wins
            IO.blocking(java.nio.file.Files.createFile(claimFile(j.revisionId).toNioPath)).attempt
              .flatMap {
                case Left(_) => IO.none
                case Right(_) =>
                  val running = j.copy(status = "running", updatedAt = at.toString)
                  write(running).as(Some(running))
              }
        }
      }

    private def finish(rev: String, f: IngestionJob => IngestionJob): IO[Unit] =
      JsonFiles.read[IngestionJob](file(rev)).flatMap {
        case None => IO.unit
        case Some(j) => write(f(j))
      } *> Files[IO].deleteIfExists(claimFile(rev)).void

    def complete(rev: String): IO[Unit] =
      now.flatMap(t => finish(rev, _.copy(status = "ready", error = None, updatedAt = t)))

    def fail(rev: String, error: String, retryAt: Option[Instant]): IO[Unit] =
      now.flatMap(t =>
        finish(
          rev,
          j =>
            retryAt match
              case Some(r) =>
                j.copy(
                  status = "pending",
                  attempts = j.attempts + 1,
                  error = Some(error),
                  updatedAt = t,
                  nextAttemptAt = Some(r.toString)
                )
              case None =>
                j.copy(
                  status = "failed",
                  attempts = j.attempts + 1,
                  error = Some(error),
                  updatedAt = t,
                  nextAttemptAt = None
                )
        )
      )

    def status(rev: String): IO[Option[IngestionJob]] =
      if !Ids.valid(rev) then IO.none else JsonFiles.read[IngestionJob](file(rev))
    def list: IO[List[IngestionJob]] = JsonFiles.list[IngestionJob](dir)
