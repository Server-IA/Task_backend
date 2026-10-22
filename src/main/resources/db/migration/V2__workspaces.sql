-- Workspaces, miembros e invitaciones

CREATE TABLE workspaces (
    id         UUID PRIMARY KEY,
    name       VARCHAR(80) NOT NULL,
    slug       VARCHAR(48) NOT NULL,
    owner_id   UUID        NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_workspaces_slug UNIQUE (slug)
);

CREATE TABLE workspace_members (
    id           UUID PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_workspace_members UNIQUE (workspace_id, user_id),
    CONSTRAINT ck_workspace_members_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

CREATE INDEX idx_workspace_members_user ON workspace_members (user_id);

CREATE TABLE workspace_invitations (
    id            UUID PRIMARY KEY,
    workspace_id  UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    email         VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    token_hash    VARCHAR(64)  NOT NULL,
    invited_by_id UUID         REFERENCES users (id) ON DELETE SET NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    accepted      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_workspace_invitations_hash UNIQUE (token_hash),
    CONSTRAINT ck_workspace_invitations_role CHECK (role IN ('ADMIN', 'MEMBER'))
);

CREATE INDEX idx_workspace_invitations_workspace ON workspace_invitations (workspace_id, email);
