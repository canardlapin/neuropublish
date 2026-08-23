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
import neuropublish.api.AssetInventory
import neuropublish.api.Protocol.given
import neuropublish.backend.{UploadSession, UploadSessions, WorkspaceAssets}
import neuropublish.protocol.Sha256

/** `upload_sessions`: the negotiated uploads, with the proxied manifest bytes until commit. */
final class PgUploadSessions(xa: Transactor[IO]) extends UploadSessions:
  import PgUploadSessions.*

  def get(id: String)
      : IO[Option[UploadSession]] = (select ++ fr"WHERE s.id = $id").query[Row].option.map(_.map(
    _.session
  )).transact(xa)

  def put(s: UploadSession): IO[Unit] =
    val inv: Json = s.inventory.asJson
    (fr"""INSERT INTO upload_sessions
             (id, workspace_id, project_id, manifest_digest, manifest_size, parent, inventory,
              created_at, manifest_uploaded)
             VALUES (${s.id}, ${s.workspace},""" ++ Db.projectId(s.workspace, s.project) ++
      fr""", ${s.manifestDigest}, ${s.manifestSize}, ${s.parent}, $inv, ${s.created},
              ${s.manifestUploaded})
           ON CONFLICT (id) DO UPDATE SET manifest_uploaded = EXCLUDED.manifest_uploaded""")
      .update.run.void.transact(xa)

  def putManifest(id: String, bytes: Array[Byte]): IO[Unit] =
    sql"""UPDATE upload_sessions SET manifest = $bytes, manifest_uploaded = true WHERE id = $id"""
      .update.run.void.transact(xa)

  def manifest(id: String): IO[Option[Array[Byte]]] =
    sql"SELECT manifest FROM upload_sessions WHERE id = $id".query[Option[Array[Byte]]].option
      .map(_.flatten).transact(xa)

  def remove(id: String): IO[Unit] =
    sql"DELETE FROM upload_sessions WHERE id = $id".update.run.void.transact(xa)

  def list: IO[
    List[UploadSession]
  ] = (select ++ fr"ORDER BY s.created_at, s.id").query[Row].to[List].map(_.map(_.session))
    .transact(xa)

object PgUploadSessions:
  final case class Row(
      id: String,
      workspace: String,
      project: String,
      manifestDigest: String,
      manifestSize: Long,
      parent: Option[String],
      inventory: Json,
      createdAt: Instant,
      manifestUploaded: Boolean
  ):
    def session: UploadSession =
      UploadSession(
        id,
        workspace,
        project,
        manifestDigest,
        manifestSize,
        parent,
        inventory.as[List[AssetInventory]].getOrElse(Nil),
        Db.render(createdAt),
        manifestUploaded
      )
  val select: Fragment =
    fr"""SELECT s.id, s.workspace_id, p.slug, s.manifest_digest, s.manifest_size, s.parent,
                s.inventory, s.created_at, s.manifest_uploaded
         FROM upload_sessions s JOIN projects p ON p.workspace_id = s.workspace_id AND p.id = s.project_id"""

/** `workspace_assets` (+ the `stored_objects` row the FK needs): which workspace may treat a digest
  * as present.
  */
final class PgWorkspaceAssets(xa: Transactor[IO]) extends WorkspaceAssets:
  def has(ws: String, d: Sha256): IO[Boolean] =
    sql"""SELECT EXISTS (SELECT 1 FROM workspace_assets
          WHERE workspace_id = $ws AND digest = ${d.render})""".query[Boolean].unique.transact(xa)

  def register(ws: String, d: Sha256, size: Long): IO[Unit] =
    (sql"""INSERT INTO stored_objects (digest, size, storage_key)
           VALUES (${d.render}, $size, ${Projections.storageKey(d)}) ON CONFLICT DO NOTHING"""
      .update.run *>
      sql"""INSERT INTO workspace_assets (workspace_id, digest) VALUES ($ws, ${d.render})
            ON CONFLICT DO NOTHING""".update.run).void.transact(xa)

  def unregister(d: Sha256): IO[Unit] =
    (sql"DELETE FROM workspace_assets WHERE digest = ${d.render}".update.run *>
      sql"DELETE FROM stored_objects WHERE digest = ${d.render}".update.run).void.transact(xa)
