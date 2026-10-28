-- Dependencias entre tareas del mismo proyecto (que sean del mismo proyecto lo valida el servicio)
--   BLOCKS:  source bloquea a target (target está «bloqueada por» source)
--   RELATES: relación simétrica; se guarda una sola vez, en cualquier dirección

CREATE TABLE issue_relations (
    id              UUID PRIMARY KEY,
    source_issue_id UUID        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    target_issue_id UUID        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    type            VARCHAR(10) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_issue_relations_type CHECK (type IN ('BLOCKS', 'RELATES')),
    CONSTRAINT ck_issue_relations_self CHECK (source_issue_id <> target_issue_id)
);

-- Una relación por par de tareas y tipo, sin importar la dirección: evita los RELATES repetidos (A-B y B-A)
-- y que dos tareas se bloqueen mutuamente.
CREATE UNIQUE INDEX uk_issue_relations_pair
    ON issue_relations (LEAST(source_issue_id, target_issue_id), GREATEST(source_issue_id, target_issue_id), type);

CREATE INDEX idx_issue_relations_source ON issue_relations (source_issue_id, type);
CREATE INDEX idx_issue_relations_target ON issue_relations (target_issue_id, type);
