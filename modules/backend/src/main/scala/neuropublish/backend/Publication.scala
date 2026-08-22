package neuropublish.backend

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import java.time.Instant
import neuropublish.api.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.Manifest

final case class UploadSession(
    id: String,
    key: ProjectKey,
    manifestDigest: Sha256,
    manifestSize: Long,
    parent: Option[String],
    inventory: List[AssetInventory],
    manifest: Option[Array[Byte]]
)

/** Upload-session lifecycle and atomic commit — the publication spine. */
final class Publication(
    objects: ObjectStore,
    revisions: RevisionStore,
    ingestion: Ingestion,
    sessions: Ref[IO, Map[String, UploadSession]],
    baseUrl: String,
    ids: IO[String]
):

  private def err(code: String, msg: String, head: Option[String] = None) =
    ApiError(code, msg, head)

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
              ).attempt.flatMap {
                case Left(e) => IO.pure(Left(err("bad_request", e.getMessage)))
                case Right(digests) =>
                  for
                    id <- ids
                    present <- digests.traverse(d => objects.exists(d).map(d -> _))
                    missing = present.collect { case (d, false) =>
                      UploadInstruction(
                        d.render,
                        s"$baseUrl/api/v1/upload-sessions/$id/objects/${d.render}"
                      )
                    }
                    s = UploadSession(id, key, md, req.manifestSize, req.parent, req.assets, None)
                    _ <- sessions.update(_ + (id -> s))
                  yield Right(UploadSessionCreated(
                    id,
                    s"$baseUrl/api/v1/upload-sessions/$id/manifest",
                    missing,
                    l
                  ))
              }
    }

  private def session(id: String): IO[Either[ApiError, UploadSession]] =
    if !Ids.valid(id) then IO.pure(Left(err("not_found", s"upload session $id does not exist")))
    else sessions.get.map(_.get(id).toRight(err("not_found", s"upload session $id does not exist")))

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
              case Some(_) => objects.put(d, bytes).map(_.leftMap(m => err("bad_request", m)))
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
        else if d.hex != s.manifestDigest.hex then
          IO.pure(Left(err(
            "bad_request",
            s"manifest digest mismatch: declared ${s.manifestDigest.render}, received ${d.render}"
          )))
        else sessions.update(_.updated(id, s.copy(manifest = Some(bytes)))).as(Right(()))
    }

  def commit(id: String, req: CommitRequest): IO[Either[ApiError, CommitResult]] =
    session(id).flatMap {
      case Left(e) => IO.pure(Left(e))
      case Right(s) =>
        s.manifest match
          case None => IO.pure(Left(err("bad_request", "manifest has not been uploaded")))
          case Some(bytes) =>
            Manifest.parse(bytes) match
              case Left(m) => IO.pure(Left(err("bad_request", s"manifest rejected: $m")))
              case Right((digest, manifest)) =>
                // every declared asset must exist with the declared size
                manifest.assets.traverse(a => objects.size(a.digest).map(sz => (a, sz))).flatMap {
                  sizes =>
                    val problems = sizes.collect {
                      case (a, None) => s"asset ${a.id} (${a.digest.render}) was not uploaded"
                      case (a, Some(sz)) if sz != a.size =>
                        s"asset ${a.id} size ${sz} differs from declared ${a.size}"
                    }
                    if problems.nonEmpty then
                      IO.pure(Left(err("bad_request", problems.mkString("; "))))
                    else
                      // derive renditions first: an unreadable asset fails the push, never the head
                      ingestion.stage(id, manifest).flatMap {
                        case Left(m) => ingestion.discard(id).as(Left(err("bad_request", m)))
                        case Right(()) =>
                          // the manifest bytes are the scientific record; store them immutably by digest
                          objects.put(digest, bytes).flatMap(r =>
                            IO.fromEither(r.leftMap(IllegalStateException(_)))
                          ) *>
                            IO.realTimeInstant.flatMap { now =>
                              revisions.commit(
                                s.key,
                                s.parent,
                                digest,
                                req.message,
                                now.toString
                              ).flatMap {
                                case Left(StaleParent(head)) =>
                                  ingestion.discard(id).as(Left(err(
                                    "stale_parent",
                                    s"parent ${s.parent.getOrElse("(none)")} is not the head of ${s.key.render}",
                                    head
                                  )))
                                case Right(rec) =>
                                  ingestion.publish(id, rec.id) *> sessions.update(_ - id) *>
                                    IO.pure(Right(CommitResult(
                                      rec.id,
                                      digest.render,
                                      rec.parent,
                                      s"$baseUrl/w/${s.key.workspace}/p/${s.key.project}/r/${rec.id}",
                                      s"$baseUrl/w/${s.key.workspace}/p/${s.key.project}/r/${rec.id}/view"
                                    )))
                              }
                            }
                      }
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

  def revision(id: String): IO[Either[ApiError, RevisionDetail]] =
    if !Ids.valid(id) then IO.pure(Left(err("not_found", s"revision $id does not exist")))
    else
      revisions.revision(id).flatMap {
        case None => IO.pure(Left(err("not_found", s"revision $id does not exist")))
        case Some(rec) =>
          objects.get(Sha256.unsafe(rec.manifestDigest)).flatMap {
            case None => IO.pure(Left(err("not_found", "manifest bytes missing")))
            case Some(bytes) =>
              IO.fromEither(Manifest.parse(bytes).leftMap(IllegalStateException(_))).flatMap {
                (_, m) =>
                  m.volumeAssetIds.traverse(a =>
                    ingestion.status(id, a).map(st =>
                      RenditionRef(
                        a,
                        st,
                        s"$baseUrl/api/v1/revisions/$id/renditions/$a/header",
                        s"$baseUrl/api/v1/revisions/$id/renditions/$a/payload"
                      )
                    )
                  ).map { rends =>
                    Right(RevisionDetail(
                      rec.id,
                      rec.workspace,
                      rec.project,
                      rec.parent,
                      rec.manifestDigest,
                      rec.message,
                      rec.committedAt,
                      m.raw,
                      rends
                    ))
                  }
              }
          }
      }
