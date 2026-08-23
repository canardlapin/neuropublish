package neuropublish.ingestion

import cats.effect.IO
import java.time.Instant
import neuropublish.backend.*

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
    log: String => IO[Unit] = IO.println,
    name: String = Worker.defaultName
):
  /** One claim; `Some(job)` when a job was processed (ready or failed/retry), `None` when idle. */
  def runOnce(now: Instant): IO[Option[IngestionJob]] =
    queue.claim(now, name).flatMap {
      case None => IO.none
      case Some(job) =>
        process(job).flatMap {
          case Right(()) =>
            queue.complete(job.revisionId) *>
              log(s"ingested   ${job.revisionId}  attempt ${job.attempts}  ok").as(Some(job))
          case Left(m) =>
            queue.fail(job.revisionId, m, now).flatMap { after =>
              val retry = after.filter(_.status == "pending").flatMap(_.nextAttemptAt)
              log(
                s"failed     ${job.revisionId}  attempt ${job.attempts}/${IngestionQueue.MaxAttempts}  $m" +
                  retry.fold("  giving up")(r => s"  retry at $r")
              ).as(Some(job))
            }
        }
    }

  private def process(job: IngestionJob): IO[Either[String, Unit]] =
    revisions.revision(job.workspace, job.revisionId).flatMap {
      case None => IO.pure(Left(s"revision ${job.revisionId} does not exist"))
      case Some(rec) =>
        // the stored manifest must still hash to the record's digest (IntegrityError otherwise)
        Derivation.manifestOf(objects, rec).flatMap {
          case None => IO.pure(Left("manifest bytes missing"))
          case Some(manifest) => Derivation.ingest(objects, renditions, job.revisionId, manifest)
        }
    }.handleError(e => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}"))

  /** Drain: run until a claim finds nothing due. */
  def drain(now: IO[Instant]): IO[Int] =
    def loop(n: Int): IO[Int] = now.flatMap(runOnce).flatMap {
      case None => IO.pure(n)
      case Some(_) => loop(n + 1)
    }
    loop(0)

object Worker:
  /** `<host>:<pid>` — what a claim records as its holder. */
  def defaultName: String =
    val host = scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse("worker")
    s"$host:${ProcessHandle.current().pid()}"
