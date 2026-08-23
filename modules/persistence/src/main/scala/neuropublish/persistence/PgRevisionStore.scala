package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import io.circe.syntax.*
import java.time.Instant
import neuropublish.backend.{
  IngestionQueue,
  ProjectKey,
  RevisionId,
  RevisionRecord,
  RevisionStore,
  StaleParent
}
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest

/** `projects` + `revisions`. Commit is one transaction: the project row is locked (`FOR UPDATE`),
  * the head compared with the declared parent, the revision inserted with the next ordinal, the
  * head advanced, the manifest projected into the read model, and — in worker mode — an
  * `ingestion_jobs` row enqueued; so two concurrent commits with the same parent serialize on the
  * row lock and exactly one sees the parent as head, and no revision exists without its projections
  * and job. `UNIQUE (project_id, ordinal)` is the backstop.
  */
final class PgRevisionStore(xa: Transactor[IO]) extends RevisionStore:
  import PgRevisionStore.*

  def createProject(key: ProjectKey): IO[Unit] =
    (sql"INSERT INTO workspaces (id) VALUES (${key.workspace}) ON CONFLICT DO NOTHING".update.run *>
      sql"""INSERT INTO projects (workspace_id, slug) VALUES (${key.workspace}, ${key.project})
            ON CONFLICT DO NOTHING""".update.run).void.transact(xa)

  def projectExists(key: ProjectKey): IO[Boolean] =
    sql"""SELECT EXISTS (SELECT 1 FROM projects
          WHERE workspace_id = ${key.workspace} AND slug = ${key.project})"""
      .query[Boolean].unique.transact(xa)

  def head(key: ProjectKey): IO[Option[String]] =
    sql"""SELECT head_revision FROM projects
          WHERE workspace_id = ${key.workspace} AND slug = ${key.project}"""
      .query[Option[String]].option.map(_.flatten).transact(xa)

  def revisions(key: ProjectKey): IO[List[RevisionRecord]] =
    (selectRevision ++
      fr"WHERE p.workspace_id = ${key.workspace} AND p.slug = ${key.project} ORDER BY r.ordinal")
      .query[Row].to[List].map(_.map(_.record)).transact(xa)

  def revision(workspace: String, id: String): IO[Option[RevisionRecord]] =
    (selectRevision ++ fr"WHERE r.workspace_id = $workspace AND r.id = $id")
      .query[Row].option.map(_.map(_.record)).transact(xa)

  def resolveId(id: String): IO[Option[RevisionRecord]] =
    (selectRevision ++ fr"WHERE r.id = $id").query[Row].option.map(_.map(_.record)).transact(xa)

  def all: IO[
    List[RevisionRecord]
  ] = (selectRevision ++ fr"ORDER BY r.committed_at, r.id").query[Row].to[List].map(_.map(_.record))
    .transact(xa)

  def commit(
      key: ProjectKey,
      parent: Option[String],
      manifestDigest: Sha256,
      message: Option[String],
      committedAt: String,
      manifest: Manifest,
      enqueue: Option[IngestionQueue]
  ): IO[Either[StaleParent, RevisionRecord]] =
    commitAndIndex(
      key,
      parent,
      manifestDigest,
      message,
      Db.ts(committedAt),
      manifest,
      enqueue.isDefined
    )
      .transact(xa)

  override def index(revision: RevisionRecord, manifest: Manifest): IO[Unit] =
    Projections.index(revision, manifest).transact(xa)

object PgRevisionStore:
  final case class Row(
      id: String,
      workspace: String,
      project: String,
      parent: Option[String],
      manifestDigest: String,
      message: Option[String],
      committedAt: Instant
  ):
    def record: RevisionRecord =
      RevisionRecord(
        id,
        workspace,
        project,
        parent,
        manifestDigest,
        message,
        Db.render(committedAt)
      )

  val selectRevision: Fragment =
    fr"""SELECT r.id, r.workspace_id, p.slug, r.parent, r.manifest_digest, r.message, r.committed_at
         FROM revisions r JOIN projects p ON p.workspace_id = r.workspace_id AND p.id = r.project_id"""

  /** The whole commit as one `ConnectionIO`: CAS on the head, insert, head update, projections, and
    * (when `enqueue`) the ingestion job — all or nothing.
    */
  def commitAndIndex(
      key: ProjectKey,
      parent: Option[String],
      manifestDigest: Sha256,
      message: Option[String],
      at: Instant,
      manifest: Manifest,
      enqueue: Boolean
  ): ConnectionIO[Either[StaleParent, RevisionRecord]] =
    sql"""SELECT id, head_revision FROM projects
          WHERE workspace_id = ${key.workspace} AND slug = ${key.project} FOR UPDATE"""
      .query[(java.util.UUID, Option[String])].option.flatMap {
        case None =>
          FC.raiseError(IllegalStateException(s"project ${key.render} does not exist"))
        case Some((_, head)) if head != parent => FC.pure(Left(StaleParent(head)))
        case Some((projectId, _)) =>
          for
            ordinal <-
              sql"SELECT coalesce(max(ordinal) + 1, 0) FROM revisions WHERE project_id = $projectId"
                .query[Int].unique
            id = RevisionId.of(key, ordinal, manifestDigest)
            rec = RevisionRecord(
              id,
              key.workspace,
              key.project,
              parent,
              manifestDigest.render,
              message,
              Db.render(at)
            )
            _ <-
              sql"""INSERT INTO revisions
                       (id, workspace_id, project_id, ordinal, parent, manifest_digest, message, committed_at)
                       VALUES ($id, ${key.workspace}, $projectId, $ordinal, $parent,
                               ${manifestDigest.render}, $message, $at)""".update.run
            _ <- sql"UPDATE projects SET head_revision = $id WHERE id = $projectId".update.run
            _ <- Projections.index(rec, manifest)
            _ <- if enqueue then IngestionJobs.enqueue(key.workspace, id) else FC.unit
          yield Right(rec)
      }

/** The rebuildable read model of a manifest: `revision_assets` (+ `stored_objects` and
  * `workspace_assets` admission rows), `analyses`, `result_fields`. Idempotent per revision, so
  * `reindex` and the commit projection run the same code; rows the manifest no longer names are
  * removed.
  */
object Projections:
  /** The object store's normalized key for a digest (`sha256/<2 hex>/<64 hex>`). */
  def storageKey(digest: Sha256): String = s"sha256/${digest.hex.take(2)}/${digest.hex}"

  def index(rev: RevisionRecord, m: Manifest): ConnectionIO[Unit] =
    val ws = rev.workspace
    val assets = m.assets.traverse_ { a =>
      sql"""INSERT INTO stored_objects (digest, size, storage_key)
            VALUES (${a.digest.render}, ${a.size}, ${storageKey(a.digest)})
            ON CONFLICT DO NOTHING""".update.run *>
        sql"""INSERT INTO workspace_assets (workspace_id, digest) VALUES ($ws, ${a.digest.render})
              ON CONFLICT DO NOTHING""".update.run *>
        sql"""INSERT INTO revision_assets
              (revision_id, workspace_id, asset_id, digest, size, media_type, catalog)
              VALUES (${rev.id}, $ws, ${a.id}, ${a.digest.render}, ${a.size}, ${a.mediaType}, ${a.catalog})
              ON CONFLICT (revision_id, asset_id) DO UPDATE SET
                digest = EXCLUDED.digest, size = EXCLUDED.size,
                media_type = EXCLUDED.media_type, catalog = EXCLUDED.catalog""".update.run
    }
    val assetIds = m.assets.map(_.id)
    val pruneAssets = assetIds.toNel match
      case None => sql"DELETE FROM revision_assets WHERE revision_id = ${rev.id}".update.run
      case Some(ids) =>
        (fr"DELETE FROM revision_assets WHERE revision_id = ${rev.id} AND" ++
          Fragments.notIn(fr"asset_id", ids)).update.run
    val analyses = m.analyses.traverse_ { a =>
      val estimands: Json = Json.fromValues(a.estimands.map(e =>
        Json.obj("id" -> e.id.asJson, "label" -> e.label.asJson, "order" -> e.order.asJson)
      ))
      sql"""INSERT INTO analyses
            (revision_id, workspace_id, analysis_id, label, sample_size, estimands, method)
            VALUES (${rev.id}, $ws, ${a.id}, ${a.label}, ${a.sampleSize}, $estimands, ${a.method})"""
        .update.run
    }
    val fields = m.resultFields.traverse_ { f =>
      val reps: Json = Json.fromValues(f.representations.map(r =>
        Json.obj("kind" -> r.kind.asJson, "asset" -> r.asset.asJson)
      ))
      sql"""INSERT INTO result_fields
            (revision_id, workspace_id, field_id, estimand, measure, domain, representations,
             ordinal, published_display)
            VALUES (${rev.id}, $ws, ${f.id}, ${f.estimand}, ${f.measure}, ${f.domain}, $reps,
                    ${f.order}, ${f.publishedDisplay})""".update.run
    }
    sql"DELETE FROM analyses WHERE revision_id = ${rev.id}".update.run *>
      sql"DELETE FROM result_fields WHERE revision_id = ${rev.id}".update.run *>
      assets *> pruneAssets *> analyses *> fields

/** Rebuild every projection from the stored manifests (architecture, "Persistence": the manifest
  * bytes are the source of truth; `analyses`/`result_fields`/`revision_assets` must be
  * rebuildable). `derived_representations` rows — the worker's output — are kept for assets the
  * manifest still names: `revision_assets` is upserted and pruned, never truncated.
  */
final class Reindex(xa: Transactor[IO], manifestBytes: Sha256 => IO[Option[Array[Byte]]]):
  def run: IO[Reindex.Report] =
    (PgRevisionStore.selectRevision ++ fr"ORDER BY r.committed_at, r.id")
      .query[PgRevisionStore.Row].to[List].transact(xa).flatMap { rows =>
        rows.foldLeftM(Reindex.Report(0, 0, Nil)) { (rep, row) =>
          val rec = row.record
          manifestBytes(Sha256.unsafe(rec.manifestDigest.stripPrefix("sha256:"))).flatMap {
            case None =>
              IO.pure(rep.copy(scanned = rep.scanned + 1, missing = rec.id :: rep.missing))
            case Some(bytes) =>
              Manifest.parse(bytes) match
                case Left(_) =>
                  IO.pure(rep.copy(scanned = rep.scanned + 1, missing = rec.id :: rep.missing))
                case Right((d, m))
                    if d.hex != Sha256.unsafe(rec.manifestDigest.stripPrefix("sha256:")).hex =>
                  // stored bytes no longer match the record: never index them
                  IO.pure(rep.copy(scanned = rep.scanned + 1, missing = rec.id :: rep.missing))
                case Right((_, m)) =>
                  Projections.index(rec, m).transact(xa)
                    .as(rep.copy(scanned = rep.scanned + 1, indexed = rep.indexed + 1))
          }
        }.map(r => r.copy(missing = r.missing.reverse))
      }

object Reindex:
  /** `missing`: revisions whose manifest bytes were absent, unparseable, or did not hash to the
    * record's digest (left untouched).
    */
  final case class Report(scanned: Int, indexed: Int, missing: List[String])
