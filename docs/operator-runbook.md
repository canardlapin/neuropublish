# Private-alpha operator runbook

This is the minimum operating contract for a private Neuropublish alpha. It is not a claim that a
public service is already deployed. The supported shape is one JVM control plane, one JVM ingestion
worker, PostgreSQL, and an S3-compatible object store behind an HTTPS ingress.

The bundled Compose file is a local rehearsal with MinIO. A hosted alpha should use an approved,
durable PostgreSQL service and object store, replace the rehearsal object URL with HTTPS, restrict
bucket CORS to the exact public origin, and pin the built application image by digest.

## Build and configuration

Build the same image for the backend and worker:

```bash
docker build -f ops/Dockerfile -t neuropublish-alpha:local .
```

Required secrets belong in the deployment secret store, never in Git:

- `NP_DATABASE_PASSWORD` through `NP_DATABASE_URL`/`NP_DATABASE_USER`;
- `NP_S3_ACCESS_KEY` and `NP_S3_SECRET_KEY`, unless the platform supplies workload identity;
- `NP_OWNER_PASSWORD` for first bootstrap only;
- TLS certificates at the ingress.

Required non-secret configuration:

- `NP_BASE_URL=https://...` — the one public origin; it controls share/device URLs, `Secure`
  cookies, and HSTS;
- `NP_HOST=0.0.0.0`, `NP_PORT=8080`, `NP_STATIC_DIR=/app/public` in a container;
- `NP_DATABASE_URL`, `NP_S3_BUCKET`, `NP_S3_REGION`, and optionally `NP_S3_ENDPOINT`;
- `NP_S3_PUBLIC_ENDPOINT` only when presigned URLs need a browser-visible origin different from
  the backend's service-network endpoint (the local Compose rehearsal is such a case);
- `NP_INGESTION=worker` for the two-process alpha;
- one bootstrap `NP_WORKSPACE`, `NP_PROJECT`, and `NP_OWNER_EMAIL`;
- `NP_AUTH_ATTEMPTS` and `NP_AUTH_WINDOW_SECONDS` (defaults: 10 attempts/account/minute).

Do not set the deprecated `NP_LEGACY_TOKEN`. The browser uses an HTTP-only session cookie; HPC
publishers use a project-scoped credential or a user token obtained with `npub login`.

The object bucket needs CORS from the exact `NP_BASE_URL` origin. The rehearsal applies that
origin through MinIO's global `MINIO_API_CORS_ALLOW_ORIGIN` setting because its pinned community
release rejects the bucket-level `PutBucketCors` operation. A hosted store may instead apply an
equivalent bucket policy supporting the presigned `GET` and `PUT` requests. It should reject public
listing and use encryption, object versioning, and lifecycle rules appropriate to the institution's
data classification.

## Local full-stack rehearsal

Copy `ops/alpha.env.example` to a private temporary path and replace every `change-me` value. For a
local HTTP rehearsal only, set `NP_BASE_URL=http://127.0.0.1:8080`. Then:

```bash
docker compose --env-file /secure/path/alpha.env -f ops/compose.alpha.yml up --build -d
curl --fail http://127.0.0.1:8080/api/v1/health
NP_E2E_MODE=full scripts/e2e.sh
```

The first command proves the deployable image starts with the reference topology. The full E2E
gate independently proves PostgreSQL, presigned MinIO transfers, worker ingestion, member login,
project credentials, revision publication, saved views, sharing, volume/surface viewers, and the
foreign Julia producer.

Before calling a hosted alpha ready, check its public response rather than its loopback port:

```bash
curl --fail --include https://neuropublish.example.org/api/v1/health
```

The response must have CSP, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, a
same-origin referrer policy, and HSTS. Eleven password guesses for one normalized account inside a
minute must produce HTTP 429; a correct login must set `HttpOnly`, `Secure`, `SameSite=Lax`.

## Backup

Back up PostgreSQL and the object store as one dated recovery point. Keep the application image
digest and protocol version beside it.

1. Run `pg_dump --format=custom` against the production database.
2. Copy or inventory every live object under the Neuropublish bucket. If recovery must include
   deleted or overwritten provider versions, use provider-native version replication or snapshots;
   the bundled filesystem mirror intentionally captures the live immutable namespace only. Content
   hashes detect substitution but do not replace durable storage replication.
3. Record SHA-256 checksums for the database dump and object inventory.
4. Encrypt the recovery point, store it in a separate failure domain, and exercise retention.
5. Alert on a missed daily backup; retain at least one monthly recovery point during the alpha.

The database holds identity, authorization, revisions, views, audit records, and ingestion state.
The object store holds canonical manifests/assets and browser renditions. A backup of only one side
is incomplete. The Compose rehearsal has a fail-closed helper that creates a new destination,
exports both sides, and writes checksums:

```bash
ops/backup-alpha.sh /secure/path/alpha.env /secure/backups/neuropublish-2026-08-24
```

## Restore rehearsal

Restore into a new, isolated workspace—not over the running service.

For the bundled rehearsal, first prepare a second environment file with unused `NP_ALPHA_PORT` and
`NP_MINIO_PORT` values and a matching local `NP_BASE_URL`. Then the helper verifies every checksum,
uses a distinct Compose project and volumes, restores both stores, starts the application, and runs
`reindex`:

```bash
ops/restore-alpha-rehearsal.sh \
  /secure/path/restore.env \
  /secure/backups/neuropublish-2026-08-24 \
  neuropublish-restore-20260824
```

1. Provision an empty PostgreSQL database and private object bucket.
2. Restore object bytes and versions first, then `pg_restore` the database dump.
3. Start the exact recorded application image with publication disabled at the ingress.
4. Run `reindex` to prove every committed manifest is present and digest-valid:

   ```bash
   docker run --rm --env-file /secure/path/restore.env neuropublish-alpha@sha256:... reindex
   ```

5. Open a project, an old revision, a saved view, and a share route; fetch at least one volume and
   one surface rendition.
6. Compare revision counts, head IDs, object inventory, members, active credentials, and audit rows
   with the recovery-point receipt.
7. Destroy the isolated restore only after recording the result.

A missing manifest, digest mismatch, failed migration, or inaccessible rendition fails the restore.
Do not promote a partial recovery.

## Rotation and incident actions

- Publisher credential: create a replacement, update the HPC secret, prove one publication, then
  revoke the old credential. Never reuse or print a stored secret.
- Owner password: stop or isolate the control plane, set the new value only in `NP_NEW_PASSWORD`,
  and run:

  ```bash
  docker run --rm --env-file /secure/path/alpha.env \
    -e NP_NEW_PASSWORD neuropublish-alpha@sha256:... reset-password --email owner@example.org
  ```

  The command replaces the PBKDF2 hash, revokes all browser sessions and user tokens, and writes an
  audit event. Remove `NP_NEW_PASSWORD` immediately afterward.
- Database/object credentials: issue new credentials, roll backend and worker together, verify
  health and a private rendition, then revoke the old credentials.
- Share leak: revoke the link; immutable revision assets need not be deleted.
- Publisher compromise: revoke its project credential, inspect `audit_events`, and create a new
  revision if scientific content must be corrected. Never rewrite an old revision.

## Current scaling boundary

Authentication throttling is intentionally in-process and account-keyed. It is valid for the
single-control-plane alpha. Before adding another control-plane replica, move the limiter to a
shared ingress or datastore and add a trusted client-address policy. Device grants are also
in-memory and therefore single-instance. These are explicit promotion gates, not hidden assumptions.
