-- Physical bytes vs. logical authorization (ADR 0004): stored_objects is internal and never
-- user-queryable; workspace_assets decides whether a workspace may reference a digest;
-- catalog_assets is the public template/atlas namespace. revision_assets and
-- derived_representations are what the worker consumes and produces; analyses and
-- result_fields are rebuildable projections of the stored manifest (`reindex`).

CREATE TABLE stored_objects (
    digest      text PRIMARY KEY,              -- "sha256:<64 hex>"
    size        bigint NOT NULL CHECK (size >= 0),
    storage_key text NOT NULL,                 -- key in the object store, e.g. sha256/ab/<hex>
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workspace_assets (
    workspace_id text NOT NULL REFERENCES workspaces (id),
    digest       text NOT NULL REFERENCES stored_objects (digest),
    first_seen   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, digest)
);

CREATE TABLE catalog_assets (
    digest     text PRIMARY KEY REFERENCES stored_objects (digest),
    name       text NOT NULL,
    kind       text NOT NULL,                  -- template | atlas | ...
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE revision_assets (
    revision_id  text NOT NULL REFERENCES revisions (id) ON DELETE CASCADE,
    workspace_id text NOT NULL REFERENCES workspaces (id),
    asset_id     text NOT NULL,                -- the manifest's asset id
    digest       text NOT NULL,
    size         bigint NOT NULL,
    media_type   text NOT NULL,
    catalog      text,
    PRIMARY KEY (revision_id, asset_id),
    FOREIGN KEY (workspace_id, digest) REFERENCES workspace_assets (workspace_id, digest)
);

CREATE TABLE derived_representations (
    revision_id  text NOT NULL,
    asset_id     text NOT NULL,
    kind         text NOT NULL DEFAULT 'volume-f32',
    status       text NOT NULL CHECK (status IN ('pending', 'running', 'ready', 'failed')),
    header_key   text,                         -- object-store key of the rendition header
    payload_key  text,                         -- object-store key of the rendition payload
    error        text,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (revision_id, asset_id, kind),
    FOREIGN KEY (revision_id, asset_id)
        REFERENCES revision_assets (revision_id, asset_id) ON DELETE CASCADE
);

CREATE TABLE analyses (
    revision_id  text NOT NULL REFERENCES revisions (id) ON DELETE CASCADE,
    workspace_id text NOT NULL REFERENCES workspaces (id),
    analysis_id  text NOT NULL,
    label        text NOT NULL,
    sample_size  integer,
    estimands    jsonb NOT NULL,
    method       jsonb,
    PRIMARY KEY (revision_id, analysis_id)
);

CREATE TABLE result_fields (
    revision_id       text NOT NULL REFERENCES revisions (id) ON DELETE CASCADE,
    workspace_id      text NOT NULL REFERENCES workspaces (id),
    field_id          text NOT NULL,
    estimand          text NOT NULL,
    measure           text NOT NULL,
    domain            text NOT NULL,
    representations   jsonb NOT NULL,          -- [{kind, asset}]
    ordinal           integer,
    published_display jsonb,
    PRIMARY KEY (revision_id, field_id)
);
CREATE INDEX result_fields_workspace_idx ON result_fields (workspace_id, estimand, measure);
