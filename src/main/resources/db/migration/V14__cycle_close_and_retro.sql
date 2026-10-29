-- Cierre de ciclos y retrospectiva
--   closed_at / closed_by_id: cuándo y quién cerró el ciclo (un ciclo cerrado ya no se edita ni recibe tareas)
--   carried_to_cycle / carried_to_backlog: tareas sin terminar que el cierre pasó al ciclo siguiente o sacó del
--   ciclo (al backlog); se guardan para el resumen de la retrospectiva
--   retro_started_at / retro_finished_at: la retrospectiva se abre al cerrar el ciclo (o la abre el project
--   manager) y al terminarla sus notas y votos quedan de solo lectura

ALTER TABLE cycles
    ADD COLUMN closed_at          TIMESTAMPTZ,
    ADD COLUMN closed_by_id       UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN carried_to_cycle   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN carried_to_backlog INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN retro_started_at   TIMESTAMPTZ,
    ADD COLUMN retro_finished_at  TIMESTAMPTZ,
    ADD CONSTRAINT ck_cycles_retro CHECK (retro_finished_at IS NULL OR retro_started_at IS NOT NULL);

-- Notas de la retrospectiva de un ciclo, en tres columnas: qué salió bien, qué mejorar y acciones
CREATE TABLE retro_notes (
    id           UUID PRIMARY KEY,
    cycle_id     UUID         NOT NULL REFERENCES cycles (id) ON DELETE CASCADE,
    -- "column" es palabra reservada en SQL
    retro_column VARCHAR(10)  NOT NULL,
    text         VARCHAR(500) NOT NULL,
    author_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Tarea creada a partir de una acción; si se borra la tarea, la acción se puede volver a convertir
    issue_id     UUID         REFERENCES issues (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_retro_notes_column CHECK (retro_column IN ('WENT_WELL', 'IMPROVE', 'ACTION'))
);

CREATE INDEX idx_retro_notes_cycle ON retro_notes (cycle_id, created_at);
CREATE INDEX idx_retro_notes_issue ON retro_notes (issue_id) WHERE issue_id IS NOT NULL;
CREATE INDEX idx_retro_notes_author ON retro_notes (author_id);

-- Votos: uno por usuario y nota (el máximo por retrospectiva lo valida el servicio)
CREATE TABLE retro_note_votes (
    note_id UUID NOT NULL REFERENCES retro_notes (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (note_id, user_id)
);

CREATE INDEX idx_retro_note_votes_user ON retro_note_votes (user_id);
