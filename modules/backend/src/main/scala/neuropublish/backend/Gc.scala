package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import java.time.Instant
import neuropublish.protocol.Sha256
import scala.concurrent.duration.*

/** Orphan cleanup (architecture: "Objects not referenced by a committed revision are eligible for
  * garbage collection, never immediate deletion during a failed commit"). Deletion is immediate
  * once an object qualifies; there is no recycle bin yet.
  *
  * An object is deleted when all of: no committed revision's manifest digest or asset inventory
  * references it; no unfinished upload session younger than `olderThan` declares it; and the object
  * itself is older than `olderThan` (an upload in flight before its session negotiated, or whose
  * session record was lost, is still safe). The reference set is recomputed immediately before each
  * deletion, so a revision committed while the run is under way keeps its objects. Upload sessions
  * older than the threshold are dropped first (with whatever they staged), and a deleted digest is
  * unregistered from every workspace. Rendition blobs of revisions that no longer exist go the same
  * way. Never runs inside a commit, and refuses to run when the revision set cannot be enumerated
  * or a stored manifest fails its integrity check.
  */
object Gc:
  final case class Report(
      scanned: Int,
      referenced: Int,
      deleted: List[Sha256],
      kept: List[Sha256],
      renditionsDeleted: List[String],
      sessionsDropped: Int,
      dryRun: Boolean
  )

  def run(
      revisions: RevisionStore,
      objects: ObjectStore,
      renditions: RenditionStore,
      sessions: UploadSessions,
      assets: WorkspaceAssets,
      audit: Audit,
      olderThan: FiniteDuration,
      dryRun: Boolean,
      now: Instant
  ): IO[Report] =
    val cutoff = now.minusMillis(olderThan.toMillis)

    /** Every digest a committed revision or a young session refers to, as of now. */
    def references: IO[(List[RevisionRecord], Set[String])] =
      for
        recs <- revisions.all
        manifestRefs <- recs.traverse { r =>
          val d = Sha256.unsafe(r.manifestDigest.stripPrefix("sha256:"))
          Derivation.manifestOf(objects, r).map {
            case None => Set(d.hex) // bytes missing: nothing to delete, the record still names it
            case Some(m) => m.assets.map(_.digest.hex).toSet + d.hex
          }
        }
        open <- sessions.list.map(_.filter(_.created.isAfter(cutoff)))
        sessionRefs = open.flatMap(s =>
          s.inventory.flatMap(a => Sha256.parse(a.digest).toOption.map(_.hex)) :+ s.digest.hex
        ).toSet
      yield (recs, manifestRefs.flatten.toSet ++ sessionRefs)

    for
      // abandoned sessions first: their staged bytes and their claim on objects go together
      stale <- sessions.list.map(_.filter(s => !s.created.isAfter(cutoff)))
      _ <-
        if dryRun then IO.unit
        else stale.traverse_(s => objects.deleteStaging(s.id) *> sessions.remove(s.id))
      (recs, referenced) <- references
      all <- objects.list.compile.toList
      (candidates, kept) = all.partition((d, st) =>
        !referenced.contains(d.hex) && st.lastModified.isBefore(cutoff)
      )
      deleted <-
        if dryRun then IO.pure(candidates.map(_._1))
        else
          candidates.traverseFilter { (d, _) =>
            // re-check against the live reference set right before the delete
            references.map(_._2.contains(d.hex)).flatMap {
              case true => IO.none
              case false => objects.delete(d) *> assets.unregister(d).as(Some(d))
            }
          }
      live = recs.map(_.id).toSet
      staleRenditions <- renditions.revisions.filter(r => !live.contains(r)).compile.toList
        .map(_.distinct)
      _ <- if dryRun then IO.unit else staleRenditions.traverse_(renditions.delete)
      workspaces = recs.map(_.workspace).distinct
      _ <- workspaces.traverse_(ws =>
        audit.record(
          "system:gc",
          if dryRun then "gc.dry-run" else "gc",
          ws,
          detail = Some(
            s"${deleted.length} orphaned objects, ${staleRenditions.length} stale rendition sets, ${stale.length} abandoned upload sessions; older than ${olderThan.toHours}h"
          )
        )
      )
    yield Report(
      all.length,
      all.count((d, _) => referenced.contains(d.hex)),
      deleted,
      kept.map(_._1),
      staleRenditions,
      stale.length,
      dryRun
    )

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
