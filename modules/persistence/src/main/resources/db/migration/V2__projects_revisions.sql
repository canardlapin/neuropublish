-- Projects and their linear revision history. Every child row carries workspace_id and a
-- composite foreign key back to (workspace_id, project_id), so a row can never claim one
-- workspace while referencing another's project (ADR 0004).

CREATE TABLE projects (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  text NOT NULL REFERENCES workspaces (id),
    slug          text NOT NULL,
    head_revision text,                        -- FK added below, once revisions exists
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, slug),
    UNIQUE (workspace_id, id)                  -- target of the composite FKs
);

CREATE TABLE revisions (
    id              text PRIMARY KEY,          -- RevisionId.of(project, ordinal, digest)
    workspace_id    text NOT NULL,
    project_id      uuid NOT NULL,
    ordinal         integer NOT NULL CHECK (ordinal >= 0),
    parent          text REFERENCES revisions (id),
    manifest_digest text NOT NULL,             -- "sha256:<64 hex>"; the bytes live in the object store
    message         text,
    committed_at    timestamptz NOT NULL,
    UNIQUE (project_id, ordinal),
    FOREIGN KEY (workspace_id, project_id) REFERENCES projects (workspace_id, id)
);
CREATE INDEX revisions_project_idx ON revisions (workspace_id, project_id, ordinal);

ALTER TABLE projects
    ADD CONSTRAINT projects_head_revision_fkey FOREIGN KEY (head_revision) REFERENCES revisions (id);
