package neuropublish.api

import io.circe.{Codec, Json}
import io.circe.generic.semiauto.*
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.model.UsernamePassword
import sttp.model.StatusCode

// Wire DTOs for the control plane. Kept separate from domain types (architecture:
// "Wire DTOs and validated domain types remain distinct").

final case class AssetInventory(digest: String, size: Long, mediaType: String)
final case class CreateUploadSession(
    manifestDigest: String,
    manifestSize: Long,
    parent: Option[String],
    assets: List[AssetInventory]
)

/** How to send one object. Stage 2: a signed object-store PUT (method/headers from the store);
  * Stage 1: the control plane.
  */
final case class UploadInstruction(
    digest: String,
    url: String,
    method: String = "PUT",
    headers: Map[String, String] = Map.empty
)
final case class Limits(maxObjectBytes: Long, maxObjects: Int, maxSessionBytes: Long)
final case class UploadSessionCreated(
    sessionId: String,
    manifestUrl: String,
    missing: List[UploadInstruction],
    limits: Limits
)
final case class CommitRequest(message: Option[String])
final case class CommitResult(
    revisionId: String,
    digest: String,
    parent: Option[String],
    revisionUrl: String,
    viewUrl: String
)
final case class RevisionSummary(
    id: String,
    parent: Option[String],
    digest: String,
    message: Option[String],
    committedAt: String
)
final case class ProjectSummary(
    workspace: String,
    project: String,
    head: Option[String],
    revisions: List[RevisionSummary]
)
final case class RenditionRef(
    assetId: String,
    status: String,
    headerUrl: String,
    payloadUrl: String
)

/** Ingestion state of a revision: "pending" | "running" | "ready" | "failed"; `error` only when
  * failed.
  */
final case class IngestionStatus(
    status: String,
    updatedAt: String,
    error: Option[String] = None,
    attempts: Int = 0
)
final case class RevisionDetail(
    id: String,
    workspace: String,
    project: String,
    parent: Option[String],
    digest: String,
    message: Option[String],
    committedAt: String,
    manifest: Json,
    renditions: List[RenditionRef],
    ingestion: Option[IngestionStatus] = None
)

/** Error body; `head` is populated on a stale-parent rejection so the publisher can re-push. */
/** One admission problem, addressed by JSON Pointer into the manifest ("" = whole document). */
final case class Problem(pointer: String, message: String, level: String = "error")

/** Error body; `head` on a stale-parent rejection; `problems` carries accumulated admission errors.
  */
final case class ApiError(
    code: String,
    message: String,
    head: Option[String] = None,
    problems: Option[List[Problem]] = None
)

object Protocol:
  given Codec[AssetInventory] = deriveCodec
  given Codec[CreateUploadSession] = deriveCodec
  given Codec[UploadInstruction] = deriveCodec
  given Codec[Limits] = deriveCodec
  given Codec[UploadSessionCreated] = deriveCodec
  given Codec[CommitRequest] = deriveCodec
  given Codec[CommitResult] = deriveCodec
  given Codec[RevisionSummary] = deriveCodec
  given Codec[ProjectSummary] = deriveCodec
  given Codec[RenditionRef] = deriveCodec
  given Codec[IngestionStatus] = deriveCodec
  given Codec[Problem] = deriveCodec
  given Codec[RevisionDetail] = deriveCodec
  given Codec[ApiError] = deriveCodec

  val limits: Limits = Limits(
    maxObjectBytes = 2L * 1024 * 1024 * 1024,
    maxObjects = 10_000,
    maxSessionBytes = 50L * 1024 * 1024 * 1024
  )

  private val base = endpoint.in("api" / "v1")
  // Variants share one body type, so they are discriminated by `code`.
  private def variant(status: StatusCode, code: String, desc: String) =
    oneOfVariantValueMatcher(status, jsonBody[ApiError].description(desc)) {
      case e: ApiError if e.code == code => true
    }
  private val errors = oneOf[ApiError](
    variant(StatusCode.Unauthorized, "unauthorized", "missing or invalid token"),
    variant(StatusCode.Forbidden, "forbidden", "principal is not allowed to do this here"),
    variant(StatusCode.NotFound, "not_found", "unknown project, session, or revision"),
    variant(
      StatusCode.Conflict,
      "stale_parent",
      "parent is not the project head; `head` carries it"
    ),
    variant(StatusCode.PayloadTooLarge, "payload_too_large", "declared inventory exceeds limits"),
    oneOfDefaultVariant(statusCode(
      StatusCode.BadRequest
    ).and(jsonBody[ApiError].description("invalid request or manifest")))
  )
  private val secured = base.securityIn(
    auth.bearer[Option[String]]()
  ).securityIn(cookie[Option[String]]("np_session")).errorOut(errors)

  val createUploadSession = secured.post
    .in("workspaces" / path[String]("workspace") / "projects" / path[String]("project") /
      "upload-sessions")
    .in(jsonBody[CreateUploadSession])
    .out(statusCode(StatusCode.Created).and(jsonBody[UploadSessionCreated]))
    .description(
      "Negotiate an upload: declares the manifest digest and asset inventory; returns what is missing."
    )

  val uploadObject = secured.put
    .in("upload-sessions" / path[String]("session") / "objects" / path[String]("digest"))
    .in(byteArrayBody)
    .out(statusCode(StatusCode.NoContent))
    .description(
      "Stage 1 only: objects flow through the control plane. Stage 2 replaces this with signed object-store URLs."
    )

  val uploadManifest = secured.put
    .in("upload-sessions" / path[String]("session") / "manifest")
    .in(byteArrayBody)
    .out(statusCode(StatusCode.NoContent))

  val commit = secured.post
    .in("upload-sessions" / path[String]("session") / "commit")
    .in(jsonBody[CommitRequest])
    .out(statusCode(StatusCode.Created).and(jsonBody[CommitResult]))

  val project = secured.get
    .in("workspaces" / path[String]("workspace") / "projects" / path[String]("project"))
    .out(jsonBody[ProjectSummary])

  val revision = secured.get
    .in("revisions" / path[String]("revision"))
    .out(jsonBody[RevisionDetail])

  val renditionHeader = secured.get
    .in("revisions" / path[String]("revision") / "renditions" / path[String]("asset") / "header")
    .out(stringBody)

  val renditionPayload = secured.get
    .in("revisions" / path[String]("revision") / "renditions" / path[String]("asset") / "payload")
    .out(byteArrayBody)

  val all: List[AnyEndpoint] = List(
    Endpoints.health,
    createUploadSession,
    uploadObject,
    uploadManifest,
    commit,
    project,
    revision,
    renditionHeader,
    renditionPayload
  )
