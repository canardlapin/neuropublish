package neuropublish.persistence

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import munit.CatsEffectSuite
import neuropublish.backend.{ProjectKey, Role}
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest
import scala.concurrent.duration.*

/** Persistence-specific Stage 2 criteria against a Testcontainers PostgreSQL (skipped without
  * Docker): composite FKs, concurrent commits, reindex, workspace isolation, the job queue.
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

  db.test("two concurrent commits with the same parent: exactly one success, one stale") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      (_, d, _) <- manifest
      now <- IO.realTimeInstant
      (r1, r2) <- (
        pg.revisions.commit(a, None, d, Some("one"), now.toString),
        pg.revisions.commit(a, None, d, Some("two"), now.toString)
      ).parTupled
      head <- pg.revisions.head(a)
      all <- pg.revisions.revisions(a)
      job <- pg.jobs.forRevision(head.get)
    yield
      assertEquals(List(r1, r2).count(_.isRight), 1)
      assertEquals(List(r1, r2).collect { case Left(s) => s.head }, List(head))
      assertEquals(all.map(_.id), head.toList)
      assertEquals(job.map(_.status), Some(IngestionJobs.Status.Pending))
  }

  private def counts(pg: PgStores): IO[(Long, Long, Long)] =
    (
      sql"SELECT count(*) FROM analyses".query[Long].unique,
      sql"SELECT count(*) FROM result_fields".query[Long].unique,
      sql"SELECT count(*) FROM revision_assets".query[Long].unique
    ).tupled.transact(pg.xa)

  db.test("reindex on an empty projection reproduces it from the stored manifests") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      (bytes, d, m) <- manifest
      now <- IO.realTimeInstant
      rec <- pg.revisions.commit(a, None, d, None, now.toString).map(_.toOption.get)
      _ <- pg.revisions.index(rec, m)
      before <- counts(pg)
      _ = assertEquals(
        before,
        (m.analyses.length.toLong, m.resultFields.length.toLong, m.assets.length.toLong)
      )
      _ <- sql"DELETE FROM analyses".update.run.transact(pg.xa)
      _ <- sql"DELETE FROM result_fields".update.run.transact(pg.xa)
      _ <- sql"DELETE FROM revision_assets".update.run.transact(pg.xa)
      _ <- counts(pg).map(assertEquals(_, (0L, 0L, 0L)))
      report <- pg.reindex(x => IO.pure(Option.when(x.hex == d.hex)(bytes))).run
      after <- counts(pg)
      ws <- sql"SELECT DISTINCT workspace_id FROM result_fields".query[String].to[List]
        .transact(pg.xa)
    yield
      assertEquals(report, Reindex.Report(1, 1, Nil))
      assertEquals(after, before)
      assertEquals(ws, List(a.workspace))
  }

  db.test("two workspaces never cross: projects, revisions, credentials, links, members") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      _ <- pg.revisions.createProject(b)
      ua <- pg.identity.ensureLocalUser("a@x.org", "a", "pw")
      ub <- pg.identity.ensureLocalUser("b@x.org", "b", "pw")
      _ <- pg.members.set(a.workspace, ua.id, Role.Owner)
      _ <- pg.members.set(b.workspace, ub.id, Role.Owner)
      (_, d, _) <- manifest
      now <- IO.realTimeInstant
      ra <- pg.revisions.commit(a, None, d, None, now.toString).map(_.toOption.get)
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
      // bare-id lookups carry the workspace the caller's authz compares against
      rev <- pg.revisions.revision(ra.id)
      cred <- pg.credentials.get(ca.id)
      link <- pg.links.get(la.id)
      view <- pg.views.get(va.id)
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
      assert(crossRef.isLeft, "a credential in B referencing A's project must be refused")
  }

  db.test("saved-view versions are monotonic under concurrent saves") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      u <- pg.identity.ensureLocalUser("a@x.org", "a", "pw")
      (_, d, _) <- manifest
      now <- IO.realTimeInstant
      r <- pg.revisions.commit(a, None, d, None, now.toString).map(_.toOption.get)
      v <- pg.views.create(r, "v", Json.obj(), u.id)
      _ <- (1 to 12).toList.parTraverse_(i => pg.views.update(v.id, Json.fromInt(i), u.id))
      after <- pg.views.get(v.id)
    yield assertEquals(after.get.versions.map(_.version), (1 to 13).toList)
  }

  db.test("ingestion job contract: claim with SKIP LOCKED, retry, ready") { pg =>
    for
      _ <- pg.revisions.createProject(a)
      (_, d, _) <- manifest
      now <- IO.realTimeInstant
      r <- pg.revisions.commit(a, None, d, None, now.toString).map(_.toOption.get)
      (j1, j2) <- (pg.jobs.claim("w1"), pg.jobs.claim("w2")).parTupled
      claimed = List(j1, j2).flatten
      _ = assertEquals(claimed.map(_.revisionId), List(r.id))
      _ = assertEquals(claimed.map(_.status), List(IngestionJobs.Status.Running))
      _ <- pg.jobs.fail(claimed.head.id, "boom", retry = true)
      again <- pg.jobs.claim("w1")
      _ = assertEquals(again.map(_.attempts), Some(2))
      _ <- pg.jobs.succeed(again.get.id)
      done <- pg.jobs.forRevision(r.id)
      empty <- pg.jobs.claim("w1")
    yield
      assertEquals(done.map(_.status), Some(IngestionJobs.Status.Ready))
      assertEquals(empty, None)
  }
