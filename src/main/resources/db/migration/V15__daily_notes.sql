-- Nota de la reunión diaria (daily) de un proyecto: como máximo una por día

CREATE TABLE daily_notes (
    id            UUID PRIMARY KEY,
    project_id    UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    note_date     DATE        NOT NULL,
    text          TEXT        NOT NULL,
    updated_by_id UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_daily_notes_day UNIQUE (project_id, note_date)
);

-- La daily busca la actividad de cada persona en el día anterior
CREATE INDEX idx_issue_activities_actor ON issue_activities (actor_id, created_at);
