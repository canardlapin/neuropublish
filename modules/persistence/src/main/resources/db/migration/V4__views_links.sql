-- Saved views (immutable versions) and the read-only share links that name one version.

CREATE TABLE saved_views (
    id           text PRIMARY KEY,
    workspace_id text NOT NULL,
    project_id   uuid NOT NULL,
    revision_id  text NOT NULL REFERENCES revisions (id),
    name         text NOT NULL,
    owner        text NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, project_id) REFERENCES projects (workspace_id, id)
);
CREATE INDEX saved_views_revision_idx ON saved_views (revision_id);

CREATE TABLE saved_view_versions (
    view_id  text NOT NULL REFERENCES saved_views (id) ON DELETE CASCADE,
    version  integer NOT NULL CHECK (version >= 1),
    state    jsonb NOT NULL,                   -- opaque to the server
    saved_at timestamptz NOT NULL,
    saved_by text NOT NULL REFERENCES users (id),
    PRIMARY KEY (view_id, version)
);

CREATE TABLE share_links (
    id           text PRIMARY KEY,
    workspace_id text NOT NULL,
    project_id   uuid NOT NULL,
    view_id      text NOT NULL,
    view_version integer NOT NULL,
    secret_hash  text NOT NULL UNIQUE,         -- sha256 of the bearer secret; the secret is never stored
    created_at   timestamptz NOT NULL,
    created_by   text NOT NULL REFERENCES users (id),
    expires_at   timestamptz,
    revoked_at   timestamptz,
    FOREIGN KEY (workspace_id, project_id) REFERENCES projects (workspace_id, id),
    FOREIGN KEY (view_id, view_version) REFERENCES saved_view_versions (view_id, version)
);
CREATE INDEX share_links_project_idx ON share_links (workspace_id, project_id);
