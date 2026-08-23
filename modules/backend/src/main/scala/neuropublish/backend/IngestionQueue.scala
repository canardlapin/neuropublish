package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Codec
import io.circe.generic.semiauto.*
import java.nio.file.{FileAlreadyExistsException, NoSuchFileException}
import java.time.Instant

object LocalIngestionQueue:
  /** What a `.claim` file says: who holds the job and since when. */
  final case class Claim(claimant: String, at: String)
  given Codec[Claim] = deriveCodec

/** `<data>/queue/<rev>.json`, one file per job. Producer and consumer may be different processes: a
  * claim is the atomic creation of `<rev>.claim` (claimant + timestamp), which the consumer removes
  * when it records the outcome, so two workers on one data dir never run the same job. A claim
  * older than [[IngestionQueue.Lease]] belongs to a dead worker: it is renamed aside (exactly one
  * renamer succeeds) and the job is claimed afresh.
  */
final class LocalIngestionQueue(dir: Path) extends IngestionQueue:
  import LocalIngestionQueue.*
  private def file(rev: String) = dir / s"$rev.json"
  private def claimFile(rev: String) = dir / s"$rev.claim"
  private def write(j: IngestionJob): IO[Unit] = JsonFiles.write(file(j.revisionId), j)
  private def now = IO.realTimeInstant.map(_.toString)

  def enqueue(ws: String, rev: String): IO[Unit] =
    now.flatMap(t => write(IngestionJob(ws, rev, "pending", 0, None, t, None)))

  private def due(j: IngestionJob, at: Instant): Boolean =
    (j.status == "pending" && j.nextAttemptAt.forall(n => !Instant.parse(n).isAfter(at))) ||
      j.status == "running"

  def claim(at: Instant, claimant: String): IO[Option[IngestionJob]] =
    list.flatMap { jobs =>
      jobs.filter(due(_, at)).sortBy(_.updatedAt).foldLeftM(Option.empty[IngestionJob]) {
        case (Some(taken), _) => IO.pure(Some(taken))
        case (None, j) => tryClaim(j, at, claimant)
      }
    }

  /** Attempt to claim exactly `j`: `Some` iff this call created the `.claim` file. Exposed for the
    * exclusivity test; [[claim]] is the worker's entry point.
    */
  def tryClaim(j: IngestionJob, at: Instant, claimant: String): IO[Option[IngestionJob]] =
    val rev = j.revisionId
    val cf = claimFile(rev)
    def create: IO[Boolean] =
      Files[IO].createDirectories(dir) *>
        IO.blocking(java.nio.file.Files.createFile(cf.toNioPath)).as(true).recover {
          case _: FileAlreadyExistsException => false
        }
    def stale: IO[Boolean] =
      JsonFiles.read[Claim](cf).attempt.flatMap {
        case Right(Some(c)) =>
          IO.pure(Instant.parse(c.at).plus(IngestionQueue.Lease).isBefore(at))
        case Right(None) => IO.pure(false) // vanished: its owner just finished; nothing to reclaim
        case Left(_) =>
          // not yet written (a claimant that won a moment ago) or never written (a crash between
          // create and write): the file's own age decides
          Files[IO].getLastModifiedTime(cf).map(t =>
            Instant.ofEpochMilli(t.toMillis).plus(IngestionQueue.Lease).isBefore(at)
          ).recover { case _: NoSuchFileException => false }
      }
    // rename-and-reclaim: only one renamer wins the stale claim, then creates its own
    def reclaim: IO[Boolean] =
      val aside = dir / s"$rev.claim.${java.util.UUID.randomUUID().toString.take(8)}.stale"
      Files[IO].move(cf, aside).as(true).recover { case _: NoSuchFileException => false }
        .flatTap(won => if won then Files[IO].deleteIfExists(aside).void else IO.unit)
        .flatMap(won => if won then create else IO.pure(false))
    def record: IO[Option[IngestionJob]] =
      // the claim file names the holder; the job file counts the attempt
      JsonFiles.write(cf, Claim(claimant, at.toString)) *>
        JsonFiles.read[IngestionJob](file(rev)).flatMap {
          case None => Files[IO].deleteIfExists(cf).as(None) // job deleted under us
          case Some(current) =>
            val running = current.copy(
              status = "running",
              attempts = current.attempts + 1,
              updatedAt = at.toString
            )
            write(running).as(Some(running))
        }
    create.flatMap {
      case true => record
      case false =>
        stale.flatMap {
          case false => IO.none
          case true => reclaim.flatMap(if _ then record else IO.none)
        }
    }

  private def finish(rev: String, f: IngestionJob => IngestionJob): IO[Option[IngestionJob]] =
    JsonFiles.read[IngestionJob](file(rev)).flatMap {
      case None => IO.none
      case Some(j) =>
        val next = f(j)
        write(next).as(Some(next))
    } <* Files[IO].deleteIfExists(claimFile(rev))

  def complete(rev: String): IO[Unit] =
    now.flatMap(t => finish(rev, _.copy(status = "ready", error = None, updatedAt = t))).void

  def fail(rev: String, error: String, at: Instant): IO[Option[IngestionJob]] =
    finish(
      rev,
      j =>
        if IngestionQueue.retries(j.attempts) then
          j.copy(
            status = "pending",
            error = Some(error),
            updatedAt = at.toString,
            nextAttemptAt = Some(at.plus(IngestionQueue.backoff(j.attempts)).toString)
          )
        else
          j.copy(
            status = "failed",
            error = Some(error),
            updatedAt = at.toString,
            nextAttemptAt = None
          )
    )

  def status(rev: String): IO[Option[IngestionJob]] =
    if !Ids.valid(rev) then IO.none else JsonFiles.read[IngestionJob](file(rev))
  def list: IO[List[IngestionJob]] = JsonFiles.list[IngestionJob](dir)

  /** The claim on `rev`, if any (tests). */
  def claimOf(rev: String): IO[Option[Claim]] = JsonFiles.read[Claim](claimFile(rev)).attempt.map(
    _.toOption.flatten
  )
