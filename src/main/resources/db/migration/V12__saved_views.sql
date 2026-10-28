-- Vistas guardadas de las tareas de un proyecto: personales (solo las ve su dueño) o compartidas (todo el
-- proyecto; solo el project manager las gestiona)

CREATE TABLE saved_views (
    id         UUID PRIMARY KEY,
    project_id UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    owner_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(40) NOT NULL,
    shared     BOOLEAN     NOT NULL DEFAULT FALSE,
    -- { state: [uuid], priority: [..], assignee: [uuid], label: [uuid], search, view: list|board, sort }
    filters    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Orden entre las compartidas del proyecto o entre las personales de su dueño
    position   INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_saved_views_project ON saved_views (project_id, shared, position);
CREATE INDEX idx_saved_views_owner ON saved_views (owner_id);
