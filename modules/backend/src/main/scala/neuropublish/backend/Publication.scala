package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import neuropublish.api.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest
import scala.concurrent.duration.*

/** Upload-session lifecycle and atomic commit — the publication spine.
  *
  * Two transports, chosen by whether the object store signs direct transfers
  * ([[ObjectStore.presigning]]): local mode proxies objects and the manifest through the control
  * plane, which hashes every byte before storing it; direct (S3) mode hands out presigned PUTs into
  * a session-scoped staging area, and commit verifies size and SHA-256 of every staged object
  * before a server-side copy onto its committed content-addressed key. No client ever writes a
  * committed key. Ingestion runs inline or through the worker queue ([[IngestionMode]]).
  */
final class Publication(
    objects: ObjectStore,
    revisions: RevisionStore,
    ingestion: Ingestion,
    assets: WorkspaceAssets,
    sessions: UploadSessions,
    baseUrl: String,
    ids: IO[String]
):
  private val signed = objects.presigning
  val direct: Boolean = signed.isDefined
  val uploadTtl: FiniteDuration = 1.hour

  private def err(code: String, msg: String, head: Option[String] = None) =
    ApiError(code, msg, head)

  /** Is `d` present *for this workspace*? Only digests the workspace itself uploaded and the server
    * verified are registered, so an object another tenant stored is reported missing (ADR 0004) and
    * the workspace's own upload of identical bytes is merely a no-op copy at commit.
    */
  private def present(ws: String, d: Sha256): IO[Boolean] =
    assets.has(ws, d).flatMap {
      case true => objects.exists(d)
      case false => IO.pure(false)
    }

  /** Already staged by this session with the declared size (a resumed direct upload). */
  private def staged(s: UploadSession, d: Sha256, size: Long): IO[Boolean] =
    if !direct then IO.pure(false)
    else objects.stagedStat(s.id, d).map(_.exists(_.size == size))

  private def instruction(id: String, d: Sha256, inv: AssetInventory): IO[UploadInstruction] =
    signed match
      case Some(p) => p.presignPut(id, d, inv.size, inv.mediaType, uploadTtl)
      case None =>
        IO.pure(UploadInstruction(
          d.render,
          s"$baseUrl/api/v1/upload-sessions/$id/objects/${d.render}"
        ))

  private def manifestUrl(s: UploadSession): IO[String] =
    signed.fold(IO.pure(s"$baseUrl/api/v1/upload-sessions/${s.id}/manifest"))(
      _.presignManifestPut(s.id, s.digest, s.manifestSize, uploadTtl)
    )

  /** Instructions for every declared digest the workspace does not hold and the session has not
    * staged, plus the manifest URL.
    */
  private def instructions(s: UploadSession): IO[UploadSessionCreated] =
    for
      missing <- s.inventory.distinctBy(_.digest).traverseFilter { inv =>
        val d = Sha256.unsafe(inv.digest.stripPrefix("sha256:"))
        (present(s.workspace, d), staged(s, d, inv.size)).mapN(_ || _).flatMap {
          case true => IO.none
          case false => instruction(s.id, d, inv).map(Some(_))
        }
      }
      url <- manifestUrl(s)
    yield UploadSessionCreated(s.id, url, missing, Protocol.limits)

  def createSession(
      key: ProjectKey,
      req: CreateUploadSession
  ): IO[Either[ApiError, UploadSessionCreated]] =
    revisions.projectExists(key).flatMap {
      case false => IO.pure(Left(err("not_found", s"project ${key.render} does not exist")))
      case true =>
        Sha256.parse(req.manifestDigest) match
          case Left(m) => IO.pure(Left(err("bad_request", m)))
          case Right(md) =>
            val l = Protocol.limits
            if req.assets.exists(_.size < 0) || req.manifestSize < 0 then
              IO.pure(Left(err("bad_request", "sizes must be non-negative")))
            else if req.assets.length > l.maxObjects then
              IO.pure(Left(err("payload_too_large", s"more than ${l.maxObjects} objects")))
            else if req.assets.exists(_.size > l.maxObjectBytes) then
              IO.pure(Left(err("payload_too_large", "an object exceeds the size limit")))
            else if req.assets.foldLeft(0L)((acc, a) => math.addExact(acc, a.size)) >
                l.maxSessionBytes
            then
              IO.pure(Left(err("payload_too_large", "session exceeds the size limit")))
            else
              req.assets.traverse_(a =>
                IO.fromEither(Sha256.parse(a.digest).leftMap(IllegalArgumentException(_)))
              ).attempt.flatMap {
                case Left(e) => IO.pure(Left(err("bad_request", e.getMessage)))
                case Right(()) =>
                  for
                    id <- ids
                    now <- IO.realTimeInstant
                    s = UploadSession(
                      id,
                      key.workspace,
                      key.project,
                      md.render,
                      req.manifestSize,
                      req.parent,
                      req.assets,
                      now.toString
                    )
                    _ <- sessions.put(s)
                    created <- instructions(s)
                  yield Right(created)
              }
    }

  /** Re-issue instructions for what is still missing (a long upload whose signed URLs expired). */
  def refreshSession(id: String): IO[Either[ApiError, UploadSessionCreated]] =
    session(id).flatMap(_.traverse(instructions))

  /** The project an upload session publishes to, for authorization before any mutation. */
  def sessionKey(id: String): IO[Option[ProjectKey]] = sessions.get(id).map(_.map(_.key))

  private def session(id: String): IO[Either[ApiError, UploadSession]] =
    sessions.get(id).map(_.toRight(err("not_found", s"upload session $id does not exist")))

  def uploadObject(id: String, digest: String, bytes: Array[Byte]): IO[Either[ApiError, Unit]] =
    session(id).flatMap {
      case Left(e) => IO.pure(Left(e))
      case Right(s) =>
        Sha256.parse(digest) match
          case Left(m) => IO.pure(Left(err("bad_request", m)))
          case Right(d) =>
            s.inventory.find(_.digest == d.render) match
              case None =>
                IO.pure(Left(err("bad_request", s"$digest is not in this session's inventory")))
              case Some(declared) if declared.size != bytes.length.toLong =>
                IO.pure(Left(err(
                  "bad_request",
                  s"$digest: ${bytes.length} bytes received, ${declared.size} declared"
                )))
              case Some(_) =>
                objects.put(d, bytes).flatMap {
                  case Left(m) => IO.pure(Left(err("bad_request", m)))
                  // the control plane hashed these bytes itself and the workspace sent them:
                  // it may learn they are present (a resumed push skips them)
                  case Right(()) =>
                    assets.register(s.workspace, d, bytes.length.toLong).as(Right(()))
                }
    }

  def uploadManifest(id: String, bytes: Array[Byte]): IO[Either[ApiError, Unit]] =
    session(id).flatMap {
      case Left(e) => IO.pure(Left(e))
      case Right(s) =>
        val d = Sha256.of(bytes)
        if bytes.length.toLong != s.manifestSize then
          IO.pure(Left(err(
            "bad_request",
            s"manifest: ${bytes.length} bytes received, ${s.manifestSize} declared"
          )))
        else if d.hex != s.digest.hex then
          IO.pure(Left(err(
            "bad_request",
            s"manifest digest mismatch: declared ${s.manifestDigest}, received ${d.render}"
          )))
        else sessions.putManifest(id, bytes).as(Right(()))
    }

  /** The manifest bytes: proxied through the control plane, or read back from the session's staging
    * area after a direct PUT. Either way they must hash to the declared digest.
    */
  private def manifestBytes(s: UploadSession): IO[Either[ApiError, Array[Byte]]] =
    (if !direct then sessions.manifest(s.id) else objects.getStaged(s.id, s.digest)).map {
      case None => Left(err("bad_request", "manifest has not been uploaded"))
      case Some(bytes) if bytes.length.toLong != s.manifestSize =>
        Left(err(
          "bad_request",
          s"manifest: ${bytes.length} bytes stored, ${s.manifestSize} declared"
        ))
      case Some(bytes) if Sha256.of(bytes).hex != s.digest.hex =>
        Left(err(
          "bad_request",
          s"manifest digest mismatch: declared ${s.manifestDigest}, stored ${Sha256.of(bytes).render}"
        ))
      case Some(bytes) => Right(bytes)
    }

  /** Every declared asset must exist with the declared size and hash to its digest. Direct mode
    * verifies the staged copy and promotes it; local mode re-checks the committed object's size.
    */
  private def verifyAssets(s: UploadSession, manifest: Manifest): IO[Either[ApiError, Unit]] =
    manifest.assets.traverse { a =>
      val check =
        if direct then
          present(s.workspace, a.digest).flatMap {
            case true => objects.verify(a.digest, a.size)
            case false => objects.verifyStaged(s.id, a.digest, a.size)
          }
        else objects.verify(a.digest, a.size)
      check.map(_.leftMap(m => s"asset ${a.id}: $m"))
    }.map { checks =>
      val problems = checks.collect { case Left(m) => m }
      // a failed commit never deletes: staged bytes wait for a retry or for `gc`
      if problems.isEmpty then Right(()) else Left(err("bad_request", problems.mkString("; ")))
    }

  def commit(id: String, req: CommitRequest): IO[Either[ApiError, CommitResult]] =
    session(id).flatMap {
      case Left(e) => IO.pure(Left(e))
      case Right(s) =>
        manifestBytes(s).flatMap {
          case Left(e) => IO.pure(Left(e))
          case Right(bytes) =>
            Manifest.parse(bytes) match
              case Left(problems) =>
                IO.pure(Left(ApiError(
                  "bad_request",
                  s"manifest rejected: ${problems.length} problem(s)",
                  problems = Some(problems.map(p => Problem(p.pointer, p.message)))
                )))
              case Right((digest, manifest)) =>
                verifyAssets(s, manifest).flatMap {
                  case Left(e) => IO.pure(Left(e))
                  case Right(()) =>
                    // verified staged objects become committed objects (server-side copy)
                    val promote =
                      if direct then manifest.assets.traverse_(a => objects.promote(s.id, a.digest))
                      else IO.unit
                    promote *>
                      (ingestion.mode match
                        case IngestionMode.Inline =>
                          // derive renditions first: an unreadable asset fails the push, never the head
                          ingestion.stage(manifest).flatMap {
                            case Left(m) => IO.pure(Left(err("bad_request", m)))
                            case Right(staged) =>
                              record(s, digest, manifest, bytes, req, None).flatMap {
                                case Left(e) => IO.pure(Left(e))
                                case Right(r) =>
                                  ingestion.publish(r.revisionId, staged) *>
                                    ingestion.queue.complete(r.revisionId).as(Right(r))
                              }
                          }
                        case IngestionMode.Worker =>
                          record(s, digest, manifest, bytes, req, Some(ingestion.queue)))
                }
        }
    }

  private def record(
      s: UploadSession,
      digest: Sha256,
      manifest: Manifest,
      bytes: Array[Byte],
      req: CommitRequest,
      enqueue: Option[IngestionQueue]
  ): IO[Either[ApiError, CommitResult]] =
    // the manifest bytes are the scientific record; store them immutably by digest
    objects.put(digest, bytes).flatMap(r => IO.fromEither(r.leftMap(IllegalStateException(_)))) *>
      IO.realTimeInstant.flatMap { now =>
        revisions.commit(s.key, s.parent, digest, req.message, now.toString, manifest, enqueue)
          .flatMap {
            case Left(StaleParent(head)) =>
              IO.pure(Left(err(
                "stale_parent",
                s"parent ${s.parent.getOrElse("(none)")} is not the head of ${s.key.render}",
                head
              )))
            case Right(rec) =>
              // only a committed revision makes its digests the workspace's own
              assets.registerAll(
                s.workspace,
                (digest, bytes.length.toLong) :: manifest.assets.map(a => (a.digest, a.size))
              ) *>
                sessions.remove(s.id) *>
                (if direct then objects.deleteStaging(s.id) else IO.unit)
                  .as(Right(CommitResult(
                    rec.id,
                    digest.render,
                    rec.parent,
                    s"$baseUrl/w/${s.workspace}/p/${s.project}/r/${rec.id}",
                    s"$baseUrl/w/${s.workspace}/p/${s.project}/r/${rec.id}/view"
                  )))
          }
      }

  def project(key: ProjectKey): IO[Either[ApiError, ProjectSummary]] =
    revisions.projectExists(key).flatMap {
      case false => IO.pure(Left(err("not_found", s"project ${key.render} does not exist")))
      case true =>
        (revisions.head(key), revisions.revisions(key)).mapN((h, rs) =>
          Right(ProjectSummary(
            key.workspace,
            key.project,
            h,
            rs.map(r => RevisionSummary(r.id, r.parent, r.manifestDigest, r.message, r.committedAt))
          ))
        )
    }

  /** Signed GETs for a ready rendition when the store serves bytes directly (S3 mode). */
  def signedRendition(rev: String, asset: String): IO[Option[(String, String)]] =
    if !direct || !Ids.valid(rev) || !Ids.valid(asset) then IO.none
    else
      ingestion.renditions.ready(rev, asset).flatMap {
        case false => IO.none
        case true => ingestion.renditions.signedUrls(rev, asset, RenditionStore.SignedTtl)
      }

  /** The revision `id` of `workspace`, with its ingestion state. */
  def revision(workspace: String, id: String): IO[Either[ApiError, RevisionDetail]] =
    if !Ids.valid(id) then IO.pure(Left(err("not_found", s"revision $id does not exist")))
    else
      revisions.revision(workspace, id).flatMap {
        case None => IO.pure(Left(err("not_found", s"revision $id does not exist")))
        case Some(rec) => revision(rec)
      }

  def revision(rec: RevisionRecord): IO[Either[ApiError, RevisionDetail]] =
    val id = rec.id
    Derivation.manifestOf(objects, rec).flatMap {
      case None => IO.pure(Left(err("not_found", "manifest bytes missing")))
      case Some(m) =>
        for
          job <- ingestion.queue.status(id)
          st <- ingestion.status(id, rec.committedAt, m, job)
          rends <- m.volumeAssetIds.traverse { a =>
            (ingestion.assetStatus(id, a, job), signedRendition(id, a)).mapN {
              (status, urls) =>
                val (h, p) = urls.getOrElse((
                  s"$baseUrl/api/v1/revisions/$id/renditions/$a/header",
                  s"$baseUrl/api/v1/revisions/$id/renditions/$a/payload"
                ))
                RenditionRef(a, status, h, p)
            }
          }
        yield Right(RevisionDetail(
          rec.id,
          rec.workspace,
          rec.project,
          rec.parent,
          rec.manifestDigest,
          rec.message,
          rec.committedAt,
          m.raw,
          rends,
          Some(st)
        ))
    }
