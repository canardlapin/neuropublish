package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import java.time.Instant
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest
import scala.concurrent.duration.*

/** Delayed orphan cleanup (architecture: "Objects not referenced by a committed revision are
  * eligible for delayed garbage collection, never immediate deletion during a failed commit").
  *
  * An object is deleted when all of: no committed revision's manifest digest or asset inventory
  * references it; no unfinished upload session younger than `olderThan` declares it; and the object
  * itself is older than `olderThan` (an upload in flight before its session negotiated, or whose
  * session record was lost, is still safe). Rendition blobs of revisions that no longer exist go
  * the same way. Never runs inside a commit.
  */
object Gc:
  final case class Report(
      scanned: Int,
      referenced: Int,
      deleted: List[Sha256],
      kept: List[Sha256],
      renditionsDeleted: List[String],
      dryRun: Boolean
  )

  def run(
      data: Path,
      objects: ObjectStore,
      renditions: RenditionStore,
      sessions: UploadSessions,
      audit: Audit,
      olderThan: FiniteDuration,
      dryRun: Boolean,
      now: Instant
  ): IO[Report] =
    val cutoff = now.minusMillis(olderThan.toMillis)
    for
      recs <- allRevisions(data)
      manifestRefs <- recs.traverse { r =>
        val d = Sha256.unsafe(r.manifestDigest)
        objects.get(d).map {
          case None => Set(d.hex)
          case Some(bytes) =>
            Manifest.parse(bytes).toOption.fold(Set(d.hex))((_, m) =>
              m.assets.map(_.digest.hex).toSet + d.hex
            )
        }
      }
      open <- sessions.list.map(_.filter(_.created.isAfter(cutoff)))
      sessionRefs = open.flatMap(s =>
        s.inventory.flatMap(a => Sha256.parse(a.digest).toOption.map(_.hex)) :+ s.digest.hex
      ).toSet
      referenced = manifestRefs.flatten.toSet ++ sessionRefs
      all <- objects.list.compile.toList
      (orphans, kept) = all.partition((d, st) =>
        !referenced.contains(d.hex) && st.lastModified.isBefore(cutoff)
      )
      _ <- if dryRun then IO.unit else orphans.traverse_((d, _) => objects.delete(d))
      live = recs.map(_.id).toSet
      stale <- renditions.revisions.filter(r => !live.contains(r)).compile.toList.map(_.distinct)
      _ <- if dryRun then IO.unit else stale.traverse_(renditions.delete)
      workspaces = recs.map(_.workspace).distinct
      _ <- workspaces.traverse_(ws =>
        audit.record(
          "system:gc",
          if dryRun then "gc.dry-run" else "gc",
          ws,
          detail = Some(
            s"${orphans.length} orphaned objects, ${stale.length} stale rendition sets; older than ${olderThan.toHours}h"
          )
        )
      )
    yield Report(
      all.length,
      all.count((d, _) => referenced.contains(d.hex)),
      orphans.map(_._1),
      kept.map(_._1),
      stale,
      dryRun
    )

  /** Every revision record under `<data>/revisions/` (the local read model; PostgreSQL mode will
    * query `revisions` instead).
    */
  private def allRevisions(data: Path): IO[List[RevisionRecord]] =
    JsonFiles.list[RevisionRecord](data / "revisions")

  /** `24h`, `90m`, `30s`, `2d`. */
  def parseDuration(s: String): Either[String, FiniteDuration] =
    "^(\\d+)\\s*([smhd])$".r.findFirstMatchIn(s.trim.toLowerCase) match
      case Some(m) =>
        val n = m.group(1).toLong
        Right(m.group(2) match
          case "s" => n.seconds
          case "m" => n.minutes
          case "h" => n.hours
          case _ => n.days)
      case None => Left(s"--older-than: expected <n>[smhd], got '$s'")
