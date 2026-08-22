# ADR 0004: Single-workspace alpha on a multi-workspace schema

Status: accepted (2026-08-22)

Date: 2026-08-22

## Context

The first users are one lab. The intended destination is several labs and
sites publishing into one hosted deployment, with institutional self-hosting
as a later option. Tenancy decisions made implicitly in an alpha (global
uniqueness of project slugs, unscoped asset deduplication, a single admin,
no quota columns) are the kind that later require data migration and a
security review to undo.

## Decision

The alpha serves exactly one workspace, created by an operator bootstrap
command (migrations create schema, never environment-specific lab data).
The schema and authorization model are multi-workspace from the first
migration:

- `workspaces` is a first-class table; every project, revision, asset
  record, saved view, share link, publisher credential, upload session, and
  audit event carries a workspace ID, and every repository query is
  workspace-scoped.
- Project slugs are unique within a workspace, not globally; URLs carry the
  workspace segment from day one.
- Logical authorization and physical deduplication are separate tables:
  `stored_objects(digest, storage_key, size, …)` is internal and never
  user-queryable; `workspace_assets(workspace_id, digest, …)` decides whether
  a workspace may reference the bytes; `catalog_assets(digest, …)` is the
  public template/atlas namespace. The server may share bytes across
  workspaces internally but never reveals cross-workspace existence.
- Human roles are owner, admin, member, and viewer; owners are few. The
  alpha has one or two owners and ordinary members. Publisher credentials
  are modelled separately as non-human principals with explicit project
  scopes, not as a role.
- Security policy is enforced as soon as the feature it governs exists:
  sensitivity classification and public-sharing policy from the first
  publish and share. Storage and object-count quotas ship with generous,
  enforced alpha defaults rather than unenforced columns.
- Where child tables carry `workspace_id`, composite foreign keys enforce
  that a revision's workspace equals its project's workspace; a row cannot
  claim one workspace while referencing another's parent.
- External login is represented as an internal `users` row plus
  `identities(issuer, subject)` records, so a second site's identity
  provider adds identities rather than users or schema.
- Object storage stays behind the S3-compatible algebra so an institution can
  self-host with its own bucket and IdP.

What this buys is narrow and specific: admitting a second workspace never
requires a tenant-key data migration or a change to revision identity. It
still requires workspace creation and invitation flows, admin pages, and a
review of authorization paths, background jobs, caches, signed-URL issuance,
and audit events for cross-workspace assumptions.

Before a second workspace is admitted, a follow-up ADR decides whether to
add PostgreSQL row-level security as defense in depth. If it is adopted, the
application must connect as a non-owner role or use
`FORCE ROW LEVEL SECURITY`, since table owners bypass policies by default.

## Consequences

### Benefits

- Multisite expansion never needs a tenant-key data migration.
- The dedup-oracle risk and cross-tenant authorization bugs are designed out
  before there is a second tenant to leak to.
- Self-hosting and hosted deployments share one codebase.

### Costs

- Every query carries a workspace predicate the alpha does not strictly
  need; tests must assert scoping from the start.
- URLs are one segment longer than a single-lab product would need.
- Roles and quotas are enforced from the start, so the alpha carries
  administrative surface it barely uses; the hardening stage re-verifies
  them rather than discovering them.

## Revisit criteria

- A second lab asks to publish before Stage 6: enable multi-workspace early
  rather than share one workspace.
- Federation across deployments (cross-site project references) is
  requested: that is a new ADR, not an extension of this one.

## Verification

- Integration tests create two workspaces and prove that project lookup,
  asset existence, share links, and publisher credentials never cross them,
  even in the single-workspace alpha configuration.
- `reindex` and backup restore preserve workspace scoping.
- Composite foreign keys reject a revision whose workspace differs from its
  project's.
- A publisher credential scoped to project A cannot publish to project B in
  the same workspace.

## References

- [PostgreSQL row security policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
