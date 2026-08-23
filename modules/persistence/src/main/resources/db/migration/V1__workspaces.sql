-- Users, external identities, workspaces, membership (ADR 0004: workspaces are first-class from
-- migration 1; identities(issuer, subject) map external logins onto internal users).

CREATE TABLE users (
    id            text PRIMARY KEY,
    email         text NOT NULL,
    name          text NOT NULL,
    password_hash jsonb,                       -- local provider only: {algorithm, iterations, salt, hash}
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identities (
    issuer     text NOT NULL,
    subject    text NOT NULL,                  -- lower-cased for the local provider (the email)
    user_id    text NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issuer, subject)
);
CREATE INDEX identities_user_idx ON identities (user_id);

CREATE TABLE workspaces (
    id         text PRIMARY KEY,               -- the URL slug, e.g. "rotman"
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
    workspace_id text NOT NULL REFERENCES workspaces (id),
    user_id      text NOT NULL REFERENCES users (id),
    role         text NOT NULL CHECK (role IN ('owner', 'admin', 'member', 'viewer')),
    added_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX workspace_members_user_idx ON workspace_members (user_id);
