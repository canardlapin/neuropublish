-- The ingestion worker's queue: one job per committed revision, enqueued 'pending' inside the
-- commit transaction. Contract with the worker (see neuropublish.persistence.IngestionJobs):
--
--   claim:    UPDATE ingestion_jobs SET status = 'running', attempts = attempts + 1,
--             locked_by = $worker, updated_at = now()
--             WHERE id = (SELECT id FROM ingestion_jobs WHERE status = 'pending'
--                         ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
--             RETURNING id, revision_id, attempts;
--   success:  status = 'ready';
--   failure:  status = 'pending' (retry) or 'failed' (give up), error set either way.

CREATE TABLE ingestion_jobs (
    id          bigserial PRIMARY KEY,
    revision_id text NOT NULL UNIQUE REFERENCES revisions (id) ON DELETE CASCADE,
    status      text NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending', 'running', 'ready', 'failed')),
    attempts    integer NOT NULL DEFAULT 0,
    error       text,
    locked_by   text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ingestion_jobs_pending_idx ON ingestion_jobs (created_at) WHERE status = 'pending';
