-- Ajustes de la daily de un proyecto: el tiempo sugerido por persona en «Presentar» (uno general y, si hace falta,
-- uno distinto para alguna persona), qué bloques se exponen y los puntos fijos que el equipo quiere tratar.
-- Un proyecto sin fila usa los valores por defecto (2 minutos, todo visible, sin puntos).

CREATE TABLE daily_settings (
    id                 UUID PRIMARY KEY,
    project_id         UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    seconds_per_person INT         NOT NULL DEFAULT 120,
    show_summary       BOOLEAN     NOT NULL DEFAULT TRUE,
    show_yesterday     BOOLEAN     NOT NULL DEFAULT TRUE,
    show_today         BOOLEAN     NOT NULL DEFAULT TRUE,
    show_blockers      BOOLEAN     NOT NULL DEFAULT TRUE,
    show_note          BOOLEAN     NOT NULL DEFAULT TRUE,
    -- { "<id de usuario>": segundos }: solo quienes tienen un tiempo distinto al general
    person_seconds     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- [ "Demo del sprint", "Riesgos" ]: puntos a tratar, en orden
    agenda             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    updated_by_id      UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_daily_settings_project UNIQUE (project_id),
    CONSTRAINT ck_daily_settings_seconds CHECK (seconds_per_person BETWEEN 15 AND 3600)
);
