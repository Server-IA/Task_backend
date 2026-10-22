-- Issues, asignados y etiquetas

CREATE TABLE issues (
    id            UUID PRIMARY KEY,
    project_id    UUID             NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    sequence_id   INTEGER          NOT NULL,
    title         VARCHAR(255)     NOT NULL,
    description   TEXT,
    -- Sin ON DELETE: un estado con issues no se puede borrar
    state_id      UUID             NOT NULL REFERENCES states (id),
    priority      VARCHAR(10)      NOT NULL DEFAULT 'NONE',
    parent_id     UUID             REFERENCES issues (id) ON DELETE SET NULL,
    start_date    DATE,
    due_date      DATE,
    sort_order    DOUBLE PRECISION NOT NULL,
    created_by_id UUID             REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uk_issues_sequence UNIQUE (project_id, sequence_id),
    CONSTRAINT ck_issues_priority CHECK (priority IN ('URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE')),
    CONSTRAINT ck_issues_dates CHECK (start_date IS NULL OR due_date IS NULL OR due_date >= start_date)
);

CREATE INDEX idx_issues_board ON issues (project_id, state_id, sort_order);
CREATE INDEX idx_issues_parent ON issues (parent_id);

-- Tablas intermedias de relaciones muchos a muchos (clave compuesta)
CREATE TABLE issue_assignees (
    issue_id UUID NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (issue_id, user_id)
);

CREATE INDEX idx_issue_assignees_user ON issue_assignees (user_id);

CREATE TABLE issue_labels (
    issue_id UUID NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    label_id UUID NOT NULL REFERENCES labels (id) ON DELETE CASCADE,
    PRIMARY KEY (issue_id, label_id)
);

CREATE INDEX idx_issue_labels_label ON issue_labels (label_id);
