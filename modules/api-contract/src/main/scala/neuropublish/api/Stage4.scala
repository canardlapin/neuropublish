package neuropublish.api

import io.circe.{Codec, Json}
import io.circe.generic.semiauto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

// ---------------------------------------------------------------------------
// Stage 4 contract: identity, publisher credentials, saved views, share links,
// provenance read model, audit. Wire DTOs only.
// ---------------------------------------------------------------------------

final case class LoginRequest(email: String, password: String)
final case class User(id: String, email: String, name: String)
final case class Membership(workspace: String, role: String)
final case class Me(user: User, memberships: List[Membership])

/** RFC 8628 device authorization grant, served by the control plane for `npub login`. */
final case class DeviceStart(client: String)
final case class DeviceCodes(
    deviceCode: String,
    userCode: String,
    verificationUri: String,
    verificationUriComplete: String,
    expiresIn: Int,
    interval: Int
)
final case class DevicePoll(deviceCode: String)

/** `status`: "pending" | "slow_down" | "granted" | "denied" | "expired"; `token` only when granted.
  * `slow_down` is RFC 8628's answer to polling faster than `interval`: the client treats it as
  * pending and doubles its wait.
  */
final case class DeviceToken(
    status: String,
    token: Option[String],
    tokenType: Option[String],
    user: Option[User]
)
final case class DeviceApprove(userCode: String)

/** Workspace membership management (owner/admin). A new email creates a local-provider user whose
  * one-time password is returned exactly once; an existing user is attached with the role.
  */
final case class AddMember(email: String, role: String)
final case class MemberAdded(user: User, role: String, oneTimePassword: Option[String])
final case class MemberSummary(user: User, role: String, addedAt: String)

final case class CreateCredential(name: String)

/** `secret` is returned once, at creation; only its hash is stored. */
final case class CredentialCreated(id: String, name: String, project: String, secret: String)
final case class CredentialSummary(
    id: String,
    name: String,
    project: String,
    createdAt: String,
    createdBy: String
)

final case class SaveView(name: String, state: Json)
final case class UpdateView(state: Json)
final case class ViewVersion(version: Int, state: Json, savedAt: String, savedBy: String)
final case class SavedViewDetail(
    id: String,
    name: String,
    revision: String,
    workspace: String,
    project: String,
    owner: String,
    latest: Int,
    versions: List[ViewVersion]
)
final case class SavedViewSummary(
    id: String,
    name: String,
    revision: String,
    owner: String,
    latest: Int,
    updatedAt: String
)

final case class CreateShareLink(expiresInDays: Option[Int])

/** `secret` appears once; the link is `{baseUrl}/s/{secret}`. */
final case class ShareLinkCreated(
    id: String,
    url: String,
    secret: String,
    expiresAt: Option[String]
)
final case class ShareLinkSummary(
    id: String,
    view: String,
    version: Int,
    createdAt: String,
    createdBy: String,
    expiresAt: Option[String],
    revokedAt: Option[String]
)

/** The view a link addresses: no owner, no other versions. */
final case class SharedViewRef(id: String, name: String, revision: String, project: String)

/** One immutable view version as a link viewer sees it: no saver identity. */
final case class SharedVersion(version: Int, state: Json, savedAt: String)

/** What a link viewer gets: the addressed version only, and a *presentation subset* of the revision
  * — `manifest` reduced to what the viewer renders (core, title, synopsis, warnings, analyses
  * without method payloads, resultFields, underlays, domains, assets as id/digest/size/mediaType);
  * no provenance, no open records, no message or parent, `committedAt` truncated to the date. No
  * membership, no edit affordances.
  */
final case class SharedView(
    view: SharedViewRef,
    version: SharedVersion,
    revision: RevisionDetail,
    expiresAt: Option[String]
)

final case class ProvenanceNode(
    id: String,
    kind: String,
    label: String,
    schemaId: Option[String],
    schemaVersion: Option[String],
    interpretation: String,
    payload: Json,
    hosted: Option[Boolean]
)
final case class ProvenanceEdge(from: String, to: String)

/** One facet (e.g. temporalNoise) across a set of receipts: groups of identical values with their
  * members.
  */
final case class FacetGroup(value: Json, count: Int, members: List[String])
final case class CompatibilityFacet(facet: String, shared: Boolean, groups: List[FacetGroup])
final case class Provenance(
    revision: String,
    nodes: List[ProvenanceNode],
    edges: List[ProvenanceEdge],
    receiptSchema: Option[String],
    receiptCount: Int,
    facets: List[CompatibilityFacet],
    warnings: List[Json]
)

final case class AuditEvent(
    id: String,
    at: String,
    actor: String,
    action: String,
    workspace: String,
    project: Option[String],
    subject: Option[String],
    detail: Option[String]
)

object Stage4:
  given Codec[LoginRequest] = deriveCodec
  given Codec[User] = deriveCodec
  given Codec[Membership] = deriveCodec
  given Codec[Me] = deriveCodec
  given Codec[DeviceStart] = deriveCodec
  given Codec[DeviceCodes] = deriveCodec
  given Codec[DevicePoll] = deriveCodec
  given Codec[DeviceToken] = deriveCodec
  given Codec[DeviceApprove] = deriveCodec
  given Codec[AddMember] = deriveCodec
  given Codec[MemberAdded] = deriveCodec
  given Codec[MemberSummary] = deriveCodec
  given Codec[CreateCredential] = deriveCodec
  given Codec[CredentialCreated] = deriveCodec
  given Codec[CredentialSummary] = deriveCodec
  given Codec[SaveView] = deriveCodec
  given Codec[UpdateView] = deriveCodec
  given Codec[ViewVersion] = deriveCodec
  given Codec[SavedViewDetail] = deriveCodec
  given Codec[SavedViewSummary] = deriveCodec
  given Codec[CreateShareLink] = deriveCodec
  given Codec[ShareLinkCreated] = deriveCodec
  given Codec[ShareLinkSummary] = deriveCodec
  given Codec[RevisionDetail] = Protocol.given_Codec_RevisionDetail
  given Codec[SharedViewRef] = deriveCodec
  given Codec[SharedVersion] = deriveCodec
  given Codec[SharedView] = deriveCodec
  given Codec[ProvenanceNode] = deriveCodec
  given Codec[ProvenanceEdge] = deriveCodec
  given Codec[FacetGroup] = deriveCodec
  given Codec[CompatibilityFacet] = deriveCodec
  given Codec[Provenance] = deriveCodec
  given Codec[AuditEvent] = deriveCodec
  given Codec[ApiError] = Protocol.given_Codec_ApiError

  private val base = endpoint.in("api" / "v1")
  private def variant(status: StatusCode, code: String) =
    oneOfVariantValueMatcher(status, jsonBody[ApiError]) {
      case e: ApiError if e.code == code => true
    }
  private val errors = oneOf[ApiError](
    variant(StatusCode.Unauthorized, "unauthorized"),
    variant(StatusCode.Forbidden, "forbidden"),
    variant(StatusCode.NotFound, "not_found"),
    variant(StatusCode.Conflict, "stale_parent"),
    variant(StatusCode.Gone, "revoked"),
    variant(StatusCode.TooManyRequests, "rate_limited"),
    oneOfDefaultVariant(statusCode(StatusCode.BadRequest).and(jsonBody[ApiError]))
  )

  /** Principal resolution: a browser session cookie (`np_session`), or a bearer token that is
    * either a user token (from the device flow) or a project-scoped publisher credential. Endpoints
    * declare both; the server decides which principals are acceptable for the operation.
    */
  private val secured = base.securityIn(
    auth.bearer[Option[String]]()
  ).securityIn(cookie[Option[String]]("np_session")).errorOut(errors)
  private val open = base.errorOut(errors)

  // ---- identity ----
  val login = open.post.in("auth" / "login").in(jsonBody[LoginRequest])
    .out(setCookie("np_session")).out(jsonBody[Me])
    .description("Local identity provider (alpha). Sets an HttpOnly session cookie.")
  val logout = secured.post.in(
    "auth" / "logout"
  ).out(setCookie("np_session")).out(statusCode(StatusCode.NoContent))
    .description(
      "Ends the presented principal: a session cookie is revoked and cleared; a bearer user token is revoked."
    )
  val revokeTokens = secured.delete.in("auth" / "tokens").out(statusCode(StatusCode.NoContent))
    .description("Revokes every user token of the signed-in user (sessions are untouched).")
  val me = secured.get.in("auth" / "me").out(jsonBody[Me])

  val deviceStart =
    open.post.in("auth" / "device").in(jsonBody[DeviceStart]).out(jsonBody[DeviceCodes])
      .description("RFC 8628: `npub login` obtains a user code to approve in any browser.")
  val devicePoll =
    open.post.in("auth" / "device" / "token").in(jsonBody[DevicePoll]).out(jsonBody[DeviceToken])
  val deviceApprove = secured.post.in("auth" / "device" / "approve").in(jsonBody[DeviceApprove])
    .out(statusCode(
      StatusCode.NoContent
    )).description("A signed-in browser approves the CLI's user code.")
  val deviceDeny = secured.post.in("auth" / "device" / "deny").in(jsonBody[DeviceApprove])
    .out(statusCode(StatusCode.NoContent))
    .description("A signed-in browser denies the CLI's user code; the CLI's next poll is `denied`.")

  // ---- workspace members (owner/admin) ----
  val addMember = secured.post.in("workspaces" / path[String]("workspace") / "members")
    .in(jsonBody[AddMember]).out(statusCode(StatusCode.Created).and(jsonBody[MemberAdded]))
  val listMembers = secured.get.in("workspaces" / path[String]("workspace") / "members")
    .out(jsonBody[List[MemberSummary]])

  // ---- publisher credentials (project-scoped, non-human principals; ADR 0004) ----
  val createCredential = secured.post
    .in("workspaces" / path[String]("workspace") / "projects" / path[String]("project") /
      "credentials")
    .in(jsonBody[CreateCredential]).out(statusCode(
      StatusCode.Created
    ).and(jsonBody[CredentialCreated]))
  val listCredentials = secured.get
    .in("workspaces" / path[String]("workspace") / "projects" / path[String]("project") /
      "credentials")
    .out(jsonBody[List[CredentialSummary]])
  val revokeCredential = secured.delete
    .in("workspaces" / path[String]("workspace") / "projects" / path[String]("project") /
      "credentials" / path[String]("credential"))
    .out(statusCode(StatusCode.NoContent))

  // ---- saved views (owned, named, every save a new immutable version) ----
  val saveView =
    secured.post.in("revisions" / path[String]("revision") / "views").in(jsonBody[SaveView])
      .out(statusCode(StatusCode.Created).and(jsonBody[SavedViewDetail]))
  val updateView = secured.put.in(
    "views" / path[String]("view")
  ).in(jsonBody[UpdateView]).out(jsonBody[SavedViewDetail])
  val getView = secured.get.in("views" / path[String]("view")).out(jsonBody[SavedViewDetail])
  val listViews = secured.get.in(
    "revisions" / path[String]("revision") / "views"
  ).out(jsonBody[List[SavedViewSummary]])

  // ---- share links ----
  val createShareLink = secured.post.in("views" / path[String]("view") / "versions" /
    path[Int]("version") / "links")
    .in(jsonBody[CreateShareLink]).out(statusCode(
      StatusCode.Created
    ).and(jsonBody[ShareLinkCreated]))
  val listShareLinks = secured.get
    .in("workspaces" / path[String]("workspace") / "projects" / path[String]("project") / "links")
    .out(jsonBody[List[ShareLinkSummary]])
  val revokeShareLink =
    secured.delete.in("links" / path[String]("link")).out(statusCode(StatusCode.NoContent))

  /** Public: opens a link-shared view without an account. 410 when revoked or expired. */
  val openShare = open.get.in("share" / path[String]("secret")).out(jsonBody[SharedView])

  /** Public rendition access for link viewers: the secret authorizes exactly that view's revision.
    */
  val shareRenditionHeader = open.get.in("share" / path[String]("secret") / "renditions" /
    path[String]("asset") / "header").out(stringBody)
  val shareRenditionPayload = open.get.in("share" / path[String]("secret") / "renditions" /
    path[String]("asset") / "payload").out(byteArrayBody)

  // ---- provenance read model and audit ----
  val provenance =
    secured.get.in("revisions" / path[String]("revision") / "provenance").out(jsonBody[Provenance])
  val audit = secured.get.in(
    "workspaces" / path[String]("workspace") / "audit"
  ).out(jsonBody[List[AuditEvent]])

  val all: List[AnyEndpoint] = List(
    login,
    logout,
    revokeTokens,
    me,
    deviceStart,
    devicePoll,
    deviceApprove,
    deviceDeny,
    addMember,
    listMembers,
    createCredential,
    listCredentials,
    revokeCredential,
    saveView,
    updateView,
    getView,
    listViews,
    createShareLink,
    listShareLinks,
    revokeShareLink,
    openShare,
    shareRenditionHeader,
    shareRenditionPayload,
    provenance,
    audit
  )
