-- Stage 2 spine fixes.
--
-- 1. ingestion_jobs becomes a leased queue: a job is due when pending and available_at has
--    passed, or running with a locked_at older than the lease (a dead worker); claims count
--    attempts; a retryable failure sets available_at = now + 2s·2^(attempts-1).
-- 2. Every child of revisions references (workspace_id, revision_id) so a row can never claim
--    one workspace while pointing at another's revision (ADR 0004); revisions.parent stays in
--    the same project.
-- 3. upload_sessions and workspace_assets carry what the local-fs stores kept, so PostgreSQL
--    mode has no local record state.

-- ---- 1. queue lease
ALTER TABLE ingestion_jobs
    ADD COLUMN workspace_id text,
    ADD COLUMN available_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN locked_at    timestamptz;
UPDATE ingestion_jobs j SET workspace_id = r.workspace_id FROM revisions r WHERE r.id = j.revision_id;
ALTER TABLE ingestion_jobs ALTER COLUMN workspace_id SET NOT NULL;
DROP INDEX ingestion_jobs_pending_idx;
CREATE INDEX ingestion_jobs_due_idx ON ingestion_jobs (available_at)
    WHERE status IN ('pending', 'running');

-- ---- 2. composite keys
ALTER TABLE revisions ADD CONSTRAINT revisions_workspace_id_id_key UNIQUE (workspace_id, id);
ALTER TABLE revisions ADD CONSTRAINT revisions_project_id_id_key UNIQUE (project_id, id);
ALTER TABLE revisions DROP CONSTRAINT revisions_parent_fkey;
ALTER TABLE revisions ADD CONSTRAINT revisions_parent_fkey
    FOREIGN KEY (project_id, parent) REFERENCES revisions (project_id, id);
CREATE INDEX revisions_parent_idx ON revisions (parent);

ALTER TABLE ingestion_jobs DROP CONSTRAINT ingestion_jobs_revision_id_fkey;
ALTER TABLE ingestion_jobs ADD CONSTRAINT ingestion_jobs_workspace_id_revision_id_fkey
    FOREIGN KEY (workspace_id, revision_id) REFERENCES revisions (workspace_id, id) ON DELETE CASCADE;

ALTER TABLE analyses DROP CONSTRAINT analyses_revision_id_fkey;
ALTER TABLE analyses ADD CONSTRAINT analyses_workspace_id_revision_id_fkey
    FOREIGN KEY (workspace_id, revision_id) REFERENCES revisions (workspace_id, id) ON DELETE CASCADE;

ALTER TABLE result_fields DROP CONSTRAINT result_fields_revision_id_fkey;
ALTER TABLE result_fields ADD CONSTRAINT result_fields_workspace_id_revision_id_fkey
    FOREIGN KEY (workspace_id, revision_id) REFERENCES revisions (workspace_id, id) ON DELETE CASCADE;

ALTER TABLE revision_assets DROP CONSTRAINT revision_assets_revision_id_fkey;
ALTER TABLE revision_assets ADD CONSTRAINT revision_assets_workspace_id_revision_id_fkey
    FOREIGN KEY (workspace_id, revision_id) REFERENCES revisions (workspace_id, id) ON DELETE CASCADE;

ALTER TABLE saved_views DROP CONSTRAINT saved_views_revision_id_fkey;
ALTER TABLE saved_views ADD CONSTRAINT saved_views_workspace_id_revision_id_fkey
    FOREIGN KEY (workspace_id, revision_id) REFERENCES revisions (workspace_id, id);
CREATE INDEX saved_views_project_idx ON saved_views (workspace_id, project_id);

ALTER TABLE derived_representations ADD COLUMN workspace_id text;
UPDATE derived_representations d SET workspace_id = r.workspace_id
    FROM revisions r WHERE r.id = d.revision_id;
ALTER TABLE derived_representations ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE derived_representations ADD CONSTRAINT derived_representations_workspace_id_revision_id_fkey
    FOREIGN KEY (workspace_id, revision_id) REFERENCES revisions (workspace_id, id) ON DELETE CASCADE;

-- ---- 3. upload sessions and the asset registry as the stores of record
ALTER TABLE upload_sessions
    ADD COLUMN manifest          bytea,                     -- control-plane mode: the bytes until commit
    ADD COLUMN manifest_uploaded boolean NOT NULL DEFAULT false,
    ALTER COLUMN expires_at SET DEFAULT (now() + interval '24 hours');
