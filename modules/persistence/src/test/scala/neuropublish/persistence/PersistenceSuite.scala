package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import java.time.Instant
import munit.CatsEffectSuite
import neuropublish.backend.{IngestionQueue, ProjectKey, Role, UploadSession}
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest
import scala.concurrent.duration.*

/** Persistence-specific Stage 2 criteria against a Testcontainers PostgreSQL (skipped without
  * Docker): composite FKs, concurrent commits, reindex, workspace isolation, the leased job queue,
  * upload sessions and the asset registry as stores of record.
  */
class PersistenceSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 3.minutes
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val a = ProjectKey("lab-a", "proj")
  private val b = ProjectKey("lab-b", "proj")
  private def db = ResourceFunFixture(PgTestDatabase.fresh)
  private def manifest: IO[(Array[Byte], Sha256, Manifest)] =
    Files[IO].readAll(fixtures / "reference" / "manifest.json").compile.to(Array).map { bytes =>
      val (d, m) = Manifest.parse(bytes).toOption.get
      (bytes, d, m)
    }
  private def commit(pg: PgStores, key: ProjectKey, parent: Option[String], msg: Option[String]) =
    for
      (_, d, m) <- manifest
      now <- IO.realTimeInstant
      r <- pg.revisions.commit(key, parent, d, msg, now.toString, m, Some(pg.queue))
    yield r

  db.test("operator password rotation works in PostgreSQL and its principals are revocable") { pg =>
    for
      user <- pg.identity.ensureLocalUser("owner@example.org", "owner", "old-password")
      (session, _) <- pg.sessions.create(user.id)
      token <- pg.tokens.mint(user.id, "npub")
      changed <- pg.identity.changeLocalPassword("OWNER@example.org", "new-password")
      old <- pg.identity.authenticate("owner@example.org", "old-password")
      fresh <- pg.identity.authenticate("owner@example.org", "new-password")
      sessions <- pg.sessions.revokeAll(user.id)
      tokens <- pg.tokens.revokeAll(user.id)
      sessionAfter <- pg.sessions.resolve(session)
      tokenAfter <- pg.tokens.resolve(token)
    yield
      assertEquals(changed.map(_.id), Some(user.id))
      assertEquals(old, None)
      assertEquals(fresh.map(_.id), Some(user.id))
      assertEquals((sessions, tokens), (1, 1))
      assertEquals((sessionAfter, tokenAfter), (None, None))
  }

  db.test("composite FK rejects a revision whose workspace differs from its project's") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      _ <- pg.revisions.createProject(b)
      pid <-
        sql"SELECT id FROM projects WHERE workspace_id = ${a.workspace} AND slug = ${a.project}"
          .query[java.util.UUID].unique.transact(pg.xa)
      r <- sql"""INSERT INTO revisions
                 (id, workspace_id, project_id, ordinal, manifest_digest, committed_at)
                 VALUES ('deadbeef0000', ${b.workspace}, $pid, 0, 'sha256:x', now())""".update.run
        .transact(pg.xa).attempt
    yield assert(
      r.left.exists(_.getMessage.contains("revisions_workspace_id_project_id_fkey")),
      r.toString
    )
  }

  db.test(
    "composite FKs: children cannot name another workspace's revision; parent stays in project"
  ) {
    pg =>
      for
        _ <- pg.revisions.createProject(a)
        _ <- pg.revisions.createProject(b)
        ra <- commit(pg, a, None, None).map(_.toOption.get)
        rb <- commit(pg, b, None, None).map(_.toOption.get)
        _ <- sql"DELETE FROM ingestion_jobs WHERE revision_id = ${ra.id}".update.run.transact(pg.xa)
        job <- sql"""INSERT INTO ingestion_jobs (workspace_id, revision_id)
                     VALUES (${b.workspace}, ${ra.id})""".update.run.transact(pg.xa).attempt
        analysis <- sql"""INSERT INTO analyses
                          (revision_id, workspace_id, analysis_id, label, estimands)
                          VALUES (${ra.id}, ${b.workspace}, 'x', 'x', '[]'::jsonb)""".update.run
          .transact(pg.xa).attempt
        pidA <-
          sql"SELECT id FROM projects WHERE workspace_id = ${a.workspace} AND slug = ${a.project}"
            .query[java.util.UUID].unique.transact(pg.xa)
        // a revision in A's project whose parent is B's revision
        cross <-
          sql"""INSERT INTO revisions
                       (id, workspace_id, project_id, ordinal, parent, manifest_digest, committed_at)
                       VALUES ('cafe00000001', ${a.workspace}, $pidA, 7, ${rb.id}, 'sha256:x', now())"""
            .update.run.transact(pg.xa).attempt
      yield
        assert(
          job.left.exists(_.getMessage.contains("ingestion_jobs_workspace_id_revision_id_fkey")),
          job.toString
        )
        assert(
          analysis.left.exists(_.getMessage.contains("analyses_workspace_id_revision_id_fkey")),
          analysis.toString
        )
        assert(cross.left.exists(_.getMessage.contains("revisions_parent_fkey")), cross.toString)
  }

  db.test("two concurrent commits with the same parent: exactly one success, one stale") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      (r1, r2) <- (commit(pg, a, None, Some("one")), commit(pg, a, None, Some("two"))).parTupled
      head <- pg.revisions.head(a)
      all <- pg.revisions.revisions(a)
      job <- pg.queue.status(head.get)
      fields <- sql"SELECT count(*) FROM result_fields WHERE revision_id = ${head.get}"
        .query[Long].unique.transact(pg.xa)
    yield
      assertEquals(List(r1, r2).count(_.isRight), 1)
      assertEquals(List(r1, r2).collect { case Left(s) => s.head }, List(head))
      assertEquals(all.map(_.id), head.toList)
      assertEquals(job.map(_.status), Some("pending"))
      assert(fields > 0, "projections are written in the commit transaction")
  }

  /** The projected rows, fully: what `reindex` must reproduce. */
  private def projection(pg: PgStores) =
    (
      sql"SELECT revision_id, analysis_id, label, sample_size, estimands::text FROM analyses ORDER BY 1, 2"
        .query[(String, String, String, Option[Int], String)].to[List],
      sql"""SELECT revision_id, field_id, estimand, measure, domain, representations::text, ordinal
            FROM result_fields ORDER BY 1, 2"""
        .query[(String, String, String, String, String, String, Option[Int])].to[List],
      sql"SELECT revision_id, asset_id, digest, size, media_type FROM revision_assets ORDER BY 1, 2"
        .query[(String, String, String, Long, String)].to[List]
    ).tupled.transact(pg.xa)

  db.test(
    "reindex on an empty projection reproduces every projected row from the stored manifests"
  ) {
    pg =>
      for
        _ <- pg.revisions.createProject(a)
        (bytes, d, m) <- manifest
        rec <- commit(pg, a, None, None).map(_.toOption.get)
        before <- projection(pg)
        _ = assertEquals(before._1.length, m.analyses.length)
        _ = assertEquals(before._2.map(_._2), m.resultFields.map(_.id).sorted)
        _ = assertEquals(
          before._3.map(t => (t._2, t._3)),
          m.assets.map(a => (a.id, a.digest.render)).sortBy(_._1)
        )
        _ <- sql"DELETE FROM analyses".update.run.transact(pg.xa)
        _ <- sql"DELETE FROM result_fields".update.run.transact(pg.xa)
        _ <- sql"DELETE FROM revision_assets".update.run.transact(pg.xa)
        _ <- projection(pg).map(p => assertEquals((p._1, p._2, p._3), (Nil, Nil, Nil)))
        // a row the manifest does not name must not survive a reindex either
        _ <-
          sql"""INSERT INTO revision_assets (revision_id, workspace_id, asset_id, digest, size, media_type)
                   SELECT ${rec.id}, ${a.workspace}, 'ghost', digest, 1, 'x' FROM workspace_assets LIMIT 1"""
            .update.run.transact(pg.xa)
        report <- pg.reindex(x => IO.pure(Option.when(x.hex == d.hex)(bytes))).run
        after <- projection(pg)
        ws <- sql"SELECT DISTINCT workspace_id FROM result_fields".query[String].to[List]
          .transact(pg.xa)
        // tampered manifest bytes are never indexed
        tampered <- pg.reindex(_ => IO.pure(Some(bytes ++ Array[Byte](32)))).run
      yield
        assertEquals(report, Reindex.Report(1, 1, Nil))
        assertEquals(after, before)
        assertEquals(ws, List(a.workspace))
        assertEquals(tampered, Reindex.Report(1, 0, List(rec.id)))
  }

  db.test("two workspaces never cross: projects, revisions, credentials, links, members") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      _ <- pg.revisions.createProject(b)
      ua <- pg.identity.ensureLocalUser("a@x.org", "a", "pw")
      ub <- pg.identity.ensureLocalUser("b@x.org", "b", "pw")
      _ <- pg.members.set(a.workspace, ua.id, Role.Owner)
      _ <- pg.members.set(b.workspace, ub.id, Role.Owner)
      ra <- commit(pg, a, None, None).map(_.toOption.get)
      (ca, _) <- pg.credentials.create(a, "batch", ua.id)
      va <- pg.views.create(ra, "v", Json.obj(), ua.id)
      (la, _) <- pg.links.create(va, 1, ua.id, None)
      _ <- pg.audit.record("user:" + ua.id, "publish", a.workspace)
      // the same slug in B is a different, empty project
      headB <- pg.revisions.head(b)
      revsB <- pg.revisions.revisions(b)
      credsB <- pg.credentials.list(b)
      linksB <- pg.links.list(b)
      auditB <- pg.audit.list(b.workspace)
      roleB <- pg.members.role(b.workspace, ua.id)
      memberships <- pg.members.membershipsOf(ua.id)
      // id lookups are workspace-scoped: B never sees A's records by id
      rev <- pg.revisions.revision(a.workspace, ra.id)
      cred <- pg.credentials.get(a.workspace, ca.id)
      link <- pg.links.get(a.workspace, la.id)
      view <- pg.views.get(a.workspace, va.id)
      revX <- pg.revisions.revision(b.workspace, ra.id)
      credX <- pg.credentials.get(b.workspace, ca.id)
      linkX <- pg.links.get(b.workspace, la.id)
      viewX <- pg.views.get(b.workspace, va.id)
      crossRef <- sql"""INSERT INTO publisher_credentials
                        (id, workspace_id, project_id, name, secret_hash, created_at, created_by)
                        SELECT 'c-cross', ${b.workspace}, id, 'x', 'h', now(), ${ub.id}
                        FROM projects WHERE workspace_id = ${a.workspace}""".update.run
        .transact(pg.xa).attempt
    yield
      assertEquals((headB, revsB, credsB, linksB, auditB, roleB), (None, Nil, Nil, Nil, Nil, None))
      assertEquals(memberships.map(_.workspace), List(a.workspace))
      assertEquals(rev.map(_.workspace), Some(a.workspace))
      assertEquals(cred.map(_.workspace), Some(a.workspace))
      assertEquals(link.map(_.workspace), Some(a.workspace))
      assertEquals(view.map(_.workspace), Some(a.workspace))
      assertEquals((revX, credX, linkX, viewX), (None, None, None, None))
      assert(crossRef.isLeft, "a credential in B referencing A's project must be refused")
  }

  db.test("saved-view versions are monotonic under concurrent saves") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      u <- pg.identity.ensureLocalUser("a@x.org", "a", "pw")
      r <- commit(pg, a, None, None).map(_.toOption.get)
      v <- pg.views.create(r, "v", Json.obj(), u.id)
      _ <- (1 to 12).toList.parTraverse_(i => pg.views.update(v.id, Json.fromInt(i), u.id))
      after <- pg.views.get(a.workspace, v.id)
    yield assertEquals(after.get.versions.map(_.version), (1 to 13).toList)
  }

  db.test(
    "ingestion queue: claim with SKIP LOCKED counts attempts, back off, lease, ready, failed"
  ) {
    pg =>
      for
        _ <- pg.revisions.createProject(a)
        r <- commit(pg, a, None, None).map(_.toOption.get)
        t0 <- IO.realTimeInstant
        (j1, j2) <- (pg.queue.claim(t0, "w1"), pg.queue.claim(t0, "w2")).parTupled
        claimed = List(j1, j2).flatten
        _ = assertEquals(claimed.map(_.revisionId), List(r.id))
        _ = assertEquals(claimed.map(j => (j.status, j.attempts)), List(("running", 1)))
        // still running and inside the lease: nobody else gets it
        held <- pg.queue.claim(t0.plusSeconds(60), "w3")
        _ = assertEquals(held, None)
        // past the lease the dead worker's job is reclaimable, and the attempt is counted
        reclaimed <- pg.queue.claim(t0.plus(IngestionQueue.Lease).plusSeconds(1), "w3")
        _ = assertEquals(reclaimed.map(j => (j.status, j.attempts)), Some(("running", 2)))
        failed <- pg.queue.fail(r.id, "boom", t0)
        _ = assertEquals(failed.map(j => (j.status, j.attempts)), Some(("pending", 2)))
        _ = assertEquals(
          failed.flatMap(_.nextAttemptAt).map(Instant.parse),
          Some(t0.plus(IngestionQueue.backoff(2)))
        )
        early <- pg.queue.claim(t0.plusSeconds(1), "w1")
        _ = assertEquals(early, None)
        again <- pg.queue.claim(t0.plusSeconds(10), "w1")
        _ = assertEquals(again.map(_.attempts), Some(3))
        last <- pg.queue.fail(r.id, "boom again", t0.plusSeconds(11))
        _ = assertEquals(last.map(j => (j.status, j.error)), Some(("failed", Some("boom again"))))
        none <- pg.queue.claim(t0.plusSeconds(1000), "w1")
        _ = assertEquals(none, None)
        _ <- pg.queue.enqueue(a.workspace, r.id) // operator re-run resets the job
        fresh <- pg.queue.claim(t0.plusSeconds(2000), "w1")
        _ = assertEquals(fresh.map(_.attempts), Some(1))
        _ <- pg.queue.complete(r.id)
        done <- pg.queue.status(r.id)
        listed <- pg.queue.list
      yield
        assertEquals(done.map(_.status), Some("ready"))
        assertEquals(listed.map(_.revisionId), List(r.id))
  }

  db.test("upload sessions and the workspace asset registry are stores of record in PostgreSQL") {
    pg =>
      val d = Sha256.of(Array[Byte](1, 2, 3))
      for
        _ <- pg.revisions.createProject(a)
        now <- IO.realTimeInstant
        s = UploadSession(
          "s-1",
          a.workspace,
          a.project,
          d.render,
          3,
          None,
          List(neuropublish.api.AssetInventory(d.render, 3, "application/octet-stream")),
          now.toString
        )
        _ <- pg.uploadSessions.put(s)
        got <- pg.uploadSessions.get("s-1")
        _ = assertEquals(got, Some(s))
        _ <- pg.uploadSessions.putManifest("s-1", Array[Byte](1, 2, 3))
        m <- pg.uploadSessions.manifest("s-1")
        _ = assertEquals(m.map(_.toList), Some(List[Byte](1, 2, 3)))
        flagged <- pg.uploadSessions.get("s-1").map(_.exists(_.manifestUploaded))
        _ = assert(flagged)
        _ <- pg.workspaceAssets.has(a.workspace, d).map(h => assert(!h))
        _ <- pg.workspaceAssets.register(a.workspace, d, 3)
        _ <- pg.workspaceAssets.has(a.workspace, d).map(assert(_))
        _ <- pg.workspaceAssets.has(b.workspace, d).map(h =>
          assert(!h, "registration is per workspace")
        )
        _ <- pg.workspaceAssets.unregister(d)
        _ <- pg.workspaceAssets.has(a.workspace, d).map(h => assert(!h))
        _ <- pg.uploadSessions.remove("s-1")
        listed <- pg.uploadSessions.list
      yield assertEquals(listed, Nil)
  }
