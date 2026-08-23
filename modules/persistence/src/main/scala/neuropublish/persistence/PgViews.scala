package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import java.time.Instant
import neuropublish.api.ViewVersion
import neuropublish.backend.{
  ProjectKey,
  RevisionRecord,
  Secrets,
  ShareLinkRecord,
  ShareLinks,
  ViewRecord,
  Views
}

/** `saved_views` + `saved_view_versions`. A new version locks the view row (`FOR UPDATE`) and takes
  * `max(version) + 1` inside that transaction, so concurrent saves get distinct, gap-free versions;
  * `PRIMARY KEY (view_id, version)` is the backstop.
  */
final class PgViews(xa: Transactor[IO]) extends Views:
  import PgViews.*

  def create(rev: RevisionRecord, name: String, state: Json, owner: String): IO[ViewRecord] =
    for
      id <- Views.newId
      now <- IO.realTimeInstant
      tx =
        (fr"""INSERT INTO saved_views (id, workspace_id, project_id, revision_id, name, owner, created_at)
                 VALUES ($id, ${rev.workspace},""" ++ Db.projectId(rev.workspace, rev.project) ++
          fr", ${rev.id}, $name, $owner, $now)").update.run *>
          sql"""INSERT INTO saved_view_versions (view_id, version, state, saved_at, saved_by)
              VALUES ($id, 1, $state, $now, $owner)""".update.run
      _ <- tx.transact(xa)
    yield ViewRecord(
      id,
      name,
      rev.id,
      rev.workspace,
      rev.project,
      owner,
      List(ViewVersion(1, state, Db.render(now), owner))
    )

  def resolveId(id: String): IO[Option[ViewRecord]] = getC(id).transact(xa)
  def get(workspace: String, id: String): IO[Option[ViewRecord]] =
    (selectView ++ fr"WHERE v.workspace_id = $workspace AND v.id = $id").query[Row].option.flatMap {
      case None => FC.pure(Option.empty[ViewRecord])
      case Some(r) => versions(id).map(vs => Some(r.record(vs)))
    }.transact(xa)

  def update(id: String, state: Json, savedBy: String): IO[Option[ViewRecord]] =
    IO.realTimeInstant.flatMap { now =>
      (sql"SELECT id FROM saved_views WHERE id = $id FOR UPDATE".query[String].option.flatMap {
        case None => FC.pure(Option.empty[ViewRecord])
        case Some(_) =>
          sql"""INSERT INTO saved_view_versions (view_id, version, state, saved_at, saved_by)
                SELECT $id, coalesce(max(version), 0) + 1, $state, $now, $savedBy
                FROM saved_view_versions WHERE view_id = $id""".update.run *> getC(id)
      }).transact(xa)
    }

  def listForRevision(revision: String): IO[List[ViewRecord]] =
    (selectView ++ fr"WHERE v.revision_id = $revision ORDER BY v.created_at, v.id")
      .query[Row].to[List].flatMap(_.traverse(r => versions(r.id).map(r.record))).transact(xa)

  private def getC(id: String): ConnectionIO[Option[ViewRecord]] =
    (selectView ++ fr"WHERE v.id = $id").query[Row].option.flatMap {
      case None => FC.pure(Option.empty[ViewRecord])
      case Some(r) => versions(id).map(vs => Some(r.record(vs)))
    }

  private def versions(id: String): ConnectionIO[List[ViewVersion]] =
    sql"""SELECT version, state, saved_at, saved_by FROM saved_view_versions
          WHERE view_id = $id ORDER BY version"""
      .query[(Int, Json, Instant, String)].to[List]
      .map(_.map((n, s, at, by) => ViewVersion(n, s, Db.render(at), by)))

object PgViews:
  final case class Row(
      id: String,
      name: String,
      revision: String,
      workspace: String,
      project: String,
      owner: String
  ):
    def record(versions: List[ViewVersion]): ViewRecord =
      ViewRecord(id, name, revision, workspace, project, owner, versions)
  val selectView: Fragment =
    fr"""SELECT v.id, v.name, v.revision_id, v.workspace_id, p.slug, v.owner
         FROM saved_views v JOIN projects p ON p.workspace_id = v.workspace_id AND p.id = v.project_id"""

/** `share_links`: hashed bearer secrets naming one immutable saved-view version. */
final class PgShareLinks(xa: Transactor[IO]) extends ShareLinks:
  import PgShareLinks.*

  def create(
      view: ViewRecord,
      version: Int,
      createdBy: String,
      expiresInDays: Option[Int]
  ): IO[(ShareLinkRecord, String)] =
    for
      id <- ShareLinks.newId
      secret <- ShareLinks.newSecret
      now <- IO.realTimeInstant
      exp = expiresInDays.map(d => now.plusSeconds(d.toLong * 86400))
      hash = Secrets.sha256Hex(secret)
      _ <-
      (fr"""INSERT INTO share_links
                 (id, workspace_id, project_id, view_id, view_version, secret_hash, created_at,
                  created_by, expires_at)
                 VALUES ($id, ${view.workspace},""" ++ Db.projectId(view.workspace, view.project) ++
        fr", ${view.id}, $version, $hash, $now, $createdBy, $exp)").update.run.transact(xa)
    yield (
      ShareLinkRecord(
        id,
        view.workspace,
        view.project,
        view.id,
        version,
        hash,
        Db.render(now),
        createdBy,
        Db.renderOpt(exp),
        None
      ),
      secret
    )

  def resolveId(id: String)
      : IO[Option[ShareLinkRecord]] = (select ++ fr"WHERE l.id = $id").query[Row].option.map(_.map(
    _.record
  )).transact(xa)
  def get(workspace: String, id: String): IO[Option[ShareLinkRecord]] =
    (select ++ fr"WHERE l.workspace_id = $workspace AND l.id = $id").query[Row].option
      .map(_.map(_.record)).transact(xa)

  def resolveSecret(secret: String): IO[Option[ShareLinkRecord]] =
    if secret.length > 128 then IO.none
    else
      (select ++ fr"WHERE l.secret_hash = ${Secrets.sha256Hex(secret)}")
        .query[Row].option.map(_.map(_.record)).transact(xa)

  def list(key: ProjectKey): IO[List[ShareLinkRecord]] =
    (select ++
      fr"""WHERE l.workspace_id = ${key.workspace} AND p.slug = ${key.project}
           ORDER BY l.created_at, l.id""")
      .query[Row].to[List].map(_.map(_.record)).transact(xa)

  def revoke(id: String): IO[Option[ShareLinkRecord]] =
    IO.realTimeInstant.flatMap { now =>
      sql"UPDATE share_links SET revoked_at = $now WHERE id = $id AND revoked_at IS NULL"
        .update.run.transact(xa)
    } *> resolveId(id)

object PgShareLinks:
  final case class Row(
      id: String,
      workspace: String,
      project: String,
      view: String,
      version: Int,
      secretHash: String,
      createdAt: Instant,
      createdBy: String,
      expiresAt: Option[Instant],
      revokedAt: Option[Instant]
  ):
    def record: ShareLinkRecord =
      ShareLinkRecord(
        id,
        workspace,
        project,
        view,
        version,
        secretHash,
        Db.render(createdAt),
        createdBy,
        Db.renderOpt(expiresAt),
        Db.renderOpt(revokedAt)
      )
  val select: Fragment =
    fr"""SELECT l.id, l.workspace_id, p.slug, l.view_id, l.view_version, l.secret_hash,
                l.created_at, l.created_by, l.expires_at, l.revoked_at
         FROM share_links l JOIN projects p ON p.workspace_id = l.workspace_id AND p.id = l.project_id"""
