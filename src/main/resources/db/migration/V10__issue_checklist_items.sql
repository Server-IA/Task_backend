-- Criterios de aceptación (CRITERION) y pasos de la checklist (CHECK) de cada tarea

CREATE TABLE issue_checklist_items (
    id         UUID PRIMARY KEY,
    issue_id   UUID         NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    kind       VARCHAR(10)  NOT NULL,
    text       VARCHAR(500) NOT NULL,
    done       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Orden dentro de la tarea y el tipo (0, 1, 2…)
    position   INTEGER      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_issue_checklist_items_kind CHECK (kind IN ('CRITERION', 'CHECK'))
);

CREATE INDEX idx_issue_checklist_items_issue ON issue_checklist_items (issue_id, kind, position);
