-- Non-human principals (project-scoped publisher credentials) and the hashed bearer secrets of
-- human ones (device-flow user tokens, browser sessions). Secrets are never stored, only sha256.

CREATE TABLE publisher_credentials (
    id           text PRIMARY KEY,
    workspace_id text NOT NULL,
    project_id   uuid NOT NULL,
    name         text NOT NULL,
    secret_hash  text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL,
    created_by   text NOT NULL REFERENCES users (id),
    revoked_at   timestamptz,
    FOREIGN KEY (workspace_id, project_id) REFERENCES projects (workspace_id, id)
);
CREATE INDEX publisher_credentials_project_idx ON publisher_credentials (workspace_id, project_id);

CREATE TABLE user_tokens (
    secret_hash text PRIMARY KEY,
    user_id     text NOT NULL REFERENCES users (id),
    client      text NOT NULL,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL
);
CREATE INDEX user_tokens_user_idx ON user_tokens (user_id);

CREATE TABLE sessions (
    secret_hash text PRIMARY KEY,
    user_id     text NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL
);
CREATE INDEX sessions_user_idx ON sessions (user_id);
