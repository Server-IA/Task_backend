-- Proyectos, miembros, estados y etiquetas

CREATE TABLE projects (
    id            UUID PRIMARY KEY,
    workspace_id  UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name          VARCHAR(80) NOT NULL,
    identifier    VARCHAR(12) NOT NULL,
    description   TEXT,
    issue_counter INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_projects_identifier UNIQUE (workspace_id, identifier)
);

CREATE TABLE project_members (
    id         UUID PRIMARY KEY,
    project_id UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_project_members UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_members_user ON project_members (user_id);

-- "group" es palabra reservada en SQL, por eso la columna se llama state_group
CREATE TABLE states (
    id          UUID PRIMARY KEY,
    project_id  UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name        VARCHAR(50) NOT NULL,
    color       VARCHAR(7)  NOT NULL,
    state_group VARCHAR(20) NOT NULL,
    position    INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_states_name UNIQUE (project_id, name),
    CONSTRAINT ck_states_group CHECK (state_group IN ('BACKLOG', 'UNSTARTED', 'STARTED', 'COMPLETED', 'CANCELLED'))
);

CREATE TABLE labels (
    id         UUID PRIMARY KEY,
    project_id UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name       VARCHAR(50) NOT NULL,
    color      VARCHAR(7)  NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_labels_name UNIQUE (project_id, name)
);
