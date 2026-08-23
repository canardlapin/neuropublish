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
  * plane; S3 mode hands out presigned PUTs and verifies size and checksum at commit. Ingestion runs
  * inline or through the worker queue ([[IngestionMode]]).
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

  /** Is `d` present *for this workspace*? Registered digests are; otherwise, in direct mode, an
    * object written since the earliest unfinished session of this workspace that declared it (the
    * workspace uploaded it and has not committed yet). An object another tenant stored earlier is
    * reported missing (ADR 0004) and its signed PUT rewrites identical bytes.
    */
  private def present(ws: String, d: Sha256, open: List[UploadSession]): IO[Boolean] =
    assets.has(ws, d).flatMap {
      case true => objects.exists(d)
      case false if !direct => IO.pure(false)
      case false =>
        val declaredSince = open.filter(_.inventory.exists(_.digest == d.render)).map(_.created)
        if declaredSince.isEmpty then IO.pure(false)
        else objects.stat(d).map(_.exists(st => st.lastModified.isAfter(declaredSince.min)))
    }

  private def instruction(id: String, d: Sha256, inv: AssetInventory): IO[UploadInstruction] =
    signed match
      case Some(p) => p.presignPut(d, inv.size, inv.mediaType, uploadTtl)
      case None =>
        IO.pure(UploadInstruction(
          d.render,
          s"$baseUrl/api/v1/upload-sessions/$id/objects/${d.render}"
        ))

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
              req.assets.traverse(a =>
                IO.fromEither(Sha256.parse(a.digest).leftMap(IllegalArgumentException(_)))
                  .map(a -> _)
              ).attempt.flatMap {
                case Left(e) => IO.pure(Left(err("bad_request", e.getMessage)))
                case Right(digests) =>
                  for
                    id <- ids
                    now <- IO.realTimeInstant
                    open <- sessions.list.map(_.filter(_.workspace == key.workspace))
                    missing <- digests.distinctBy(_._2.hex).traverseFilter { (inv, d) =>
                      present(key.workspace, d, open).flatMap {
                        case true => IO.none
                        case false => instruction(id, d, inv).map(Some(_))
                      }
                    }
                    manifestUrl <- signed.fold(
                      IO.pure(s"$baseUrl/api/v1/upload-sessions/$id/manifest")
                    )(_.presignPlainPut(md, uploadTtl))
                    _ <- sessions.put(UploadSession(
                      id,
                      key.workspace,
                      key.project,
                      md.render,
                      req.manifestSize,
                      req.parent,
                      req.assets,
                      now.toString
                    ))
                  yield Right(UploadSessionCreated(id, manifestUrl, missing, l))
              }
    }

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
                  // the workspace sent these bytes itself: it may learn they are present
                  case Right(()) => assets.register(s.workspace, d).as(Right(()))
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

  /** The manifest bytes: proxied through the control plane, or read back from the store after a
    * direct PUT (size and checksum verified first).
    */
  private def manifestBytes(s: UploadSession): IO[Either[ApiError, Array[Byte]]] =
    if !direct then
      sessions.manifest(s.id).map(_.toRight(err("bad_request", "manifest has not been uploaded")))
    else
      objects.verify(s.digest, s.manifestSize).flatMap {
        case Left(m) => IO.pure(Left(err("bad_request", s"manifest: $m")))
        case Right(()) =>
          objects.get(s.digest).map(_.toRight(err("bad_request", "manifest has not been uploaded")))
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
                // every declared asset must exist with the declared size and hash to its digest
                manifest.assets.traverse(a =>
                  objects.verify(a.digest, a.size).map(_.leftMap(m => s"asset ${a.id}: $m"))
                ).flatMap { checks =>
                  val problems = checks.collect { case Left(m) => m }
                  if problems.nonEmpty then
                    // a failed commit never deletes: orphans wait for `gc`
                    IO.pure(Left(err("bad_request", problems.mkString("; "))))
                  else
                    ingestion.mode match
                      case IngestionMode.Inline =>
                        // derive renditions first: an unreadable asset fails the push, never the head
                        ingestion.stage(manifest).flatMap {
                          case Left(m) => IO.pure(Left(err("bad_request", m)))
                          case Right(staged) =>
                            record(s, digest, manifest, bytes, req).flatMap {
                              case Left(e) => IO.pure(Left(e))
                              case Right(r) => ingestion.publish(r.revisionId, staged).as(Right(r))
                            }
                        }
                      case IngestionMode.Worker =>
                        record(s, digest, manifest, bytes, req).flatMap {
                          case Left(e) => IO.pure(Left(e))
                          case Right(r) => ingestion.queue.enqueue(r.revisionId).as(Right(r))
                        }
                }
        }
    }

  private def record(
      s: UploadSession,
      digest: Sha256,
      manifest: Manifest,
      bytes: Array[Byte],
      req: CommitRequest
  ): IO[Either[ApiError, CommitResult]] =
    // the manifest bytes are the scientific record; store them immutably by digest
    objects.put(digest, bytes).flatMap(r => IO.fromEither(r.leftMap(IllegalStateException(_)))) *>
      assets.registerAll(s.workspace, digest :: manifest.assets.map(_.digest)) *>
      IO.realTimeInstant.flatMap { now =>
        revisions.commit(s.key, s.parent, digest, req.message, now.toString).flatMap {
          case Left(StaleParent(head)) =>
            IO.pure(Left(err(
              "stale_parent",
              s"parent ${s.parent.getOrElse("(none)")} is not the head of ${s.key.render}",
              head
            )))
          case Right(rec) =>
            // the read model (analyses, result_fields, revision_assets); `reindex` rebuilds it
            // from the stored bytes if this fails
            revisions.index(rec, manifest) *> sessions.remove(s.id).as(Right(CommitResult(
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

  def revision(id: String): IO[Either[ApiError, RevisionDetail]] =
    if !Ids.valid(id) then IO.pure(Left(err("not_found", s"revision $id does not exist")))
    else
      revisions.revision(id).flatMap {
        case None => IO.pure(Left(err("not_found", s"revision $id does not exist")))
        case Some(rec) =>
          objects.get(Sha256.unsafe(rec.manifestDigest)).flatMap {
            case None => IO.pure(Left(err("not_found", "manifest bytes missing")))
            case Some(bytes) =>
              IO.fromEither(Manifest.parse(bytes).leftMap(ps =>
                IllegalStateException(neuropublish.protocol.json.Problem.render(ps))
              )).flatMap {
                (_, m) =>
                  for
                    job <- ingestion.queue.status(id)
                    st <- ingestion.status(id, rec.committedAt)
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
          }
      }
