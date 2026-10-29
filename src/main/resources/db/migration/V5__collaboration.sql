-- Comentarios, historial de actividad y notificaciones

CREATE TABLE comments (
    id         UUID PRIMARY KEY,
    issue_id   UUID        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    author_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_comments_issue ON comments (issue_id, created_at);

CREATE TABLE issue_activities (
    id         UUID PRIMARY KEY,
    issue_id   UUID        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    actor_id   UUID        REFERENCES users (id) ON DELETE SET NULL,
    field      VARCHAR(30) NOT NULL,
    old_value  TEXT,
    new_value  TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_issue_activities_issue ON issue_activities (issue_id, created_at);

-- "read" se renombra a is_read para mayor claridad
CREATE TABLE notifications (
    id           UUID PRIMARY KEY,
    recipient_id UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    actor_id     UUID         REFERENCES users (id) ON DELETE SET NULL,
    type         VARCHAR(30)  NOT NULL,
    message      VARCHAR(500) NOT NULL,
    issue_id     UUID         REFERENCES issues (id) ON DELETE CASCADE,
    is_read      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_notifications_type CHECK (type IN ('ISSUE_ASSIGNED', 'ISSUE_COMMENTED'))
);

CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, is_read, created_at DESC);
