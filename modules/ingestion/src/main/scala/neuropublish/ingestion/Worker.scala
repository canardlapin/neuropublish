package neuropublish.ingestion

import cats.effect.IO
import cats.syntax.all.*
import java.time.Instant
import neuropublish.backend.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest

/** The ingestion worker's unit of work: claim one job, derive its renditions into the rendition
  * store, record the outcome. Retries with exponential back off up to
  * [[IngestionQueue.MaxAttempts]] attempts; the final failure records `error` on the job, which the
  * revision reports as `ingestion.status = failed`.
  */
final class Worker(
    objects: ObjectStore,
    renditions: RenditionStore,
    queue: IngestionQueue,
    revisions: RevisionStore,
    log: String => IO[Unit] = IO.println
):
  /** One claim; `Some(job)` when a job was processed (ready or failed/retry), `None` when idle. */
  def runOnce(now: Instant): IO[Option[IngestionJob]] =
    queue.claim(now).flatMap {
      case None => IO.none
      case Some(job) =>
        process(job).flatMap {
          case Right(()) =>
            queue.complete(job.revisionId) *>
              log(s"ingested   ${job.revisionId}  attempt ${job.attempts + 1}  ok").as(Some(job))
          case Left(m) =>
            val attempt = job.attempts + 1
            val retry =
              if attempt < IngestionQueue.MaxAttempts then
                Some(now.plus(IngestionQueue.backoff(attempt)))
              else None
            queue.fail(job.revisionId, m, retry) *>
              log(
                s"failed     ${job.revisionId}  attempt $attempt/${IngestionQueue.MaxAttempts}  $m" +
                  retry.fold("  giving up")(r => s"  retry at $r")
              ).as(Some(job))
        }
    }

  private def process(job: IngestionJob): IO[Either[String, Unit]] =
    revisions.revision(job.revisionId).flatMap {
      case None => IO.pure(Left(s"revision ${job.revisionId} does not exist"))
      case Some(rec) =>
        objects.get(Sha256.unsafe(rec.manifestDigest)).flatMap {
          case None => IO.pure(Left("manifest bytes missing"))
          case Some(bytes) =>
            Manifest.parse(bytes) match
              case Left(problems) =>
                IO.pure(
                  Left(s"manifest rejected: ${neuropublish.protocol.json.Problem.render(problems)}")
                )
              case Right((_, manifest)) =>
                Derivation.ingest(objects, renditions, job.revisionId, manifest)
        }
    }.handleError(e => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}"))

  /** Drain: run until a claim finds nothing due. */
  def drain(now: IO[Instant]): IO[Int] =
    def loop(n: Int): IO[Int] = now.flatMap(runOnce).flatMap {
      case None => IO.pure(n)
      case Some(_) => loop(n + 1)
    }
    loop(0)
