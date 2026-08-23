-- Upload sessions (the durable form of the in-memory session map; used once uploads resume
-- across server restarts) and the append-only audit log.

CREATE TABLE upload_sessions (
    id              text PRIMARY KEY,
    workspace_id    text NOT NULL,
    project_id      uuid NOT NULL,
    manifest_digest text NOT NULL,
    manifest_size   bigint NOT NULL,
    parent          text,
    inventory       jsonb NOT NULL,            -- [{digest, size, mediaType}]
    state           text NOT NULL DEFAULT 'open'
                    CHECK (state IN ('open', 'committed', 'discarded')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, project_id) REFERENCES projects (workspace_id, id)
);

CREATE TABLE audit_events (
    seq          bigserial PRIMARY KEY,        -- insertion order
    id           text NOT NULL UNIQUE,
    at           timestamptz NOT NULL,
    actor        text NOT NULL,
    action       text NOT NULL,
    workspace_id text NOT NULL REFERENCES workspaces (id),
    project      text,
    subject      text,
    detail       text
);
CREATE INDEX audit_events_workspace_idx ON audit_events (workspace_id, seq);
