-- Cuándo empezó cada tarea: la primera vez que entró a un estado del grupo STARTED (no se borra al salir). El tiempo
-- de ciclo se mide desde aquí hasta completed_at («de En curso a Hecho»).

ALTER TABLE issues ADD COLUMN started_at TIMESTAMPTZ;

-- Tareas existentes, a partir del historial (que guarda el nombre del estado, comparado sin mayúsculas con los
-- estados STARTED actuales del proyecto). Se toma la más temprana de estas fechas:
--   1. la primera vez que el historial la mueve a un estado STARTED;
--   2. su creación, si el primer cambio de estado del historial sale de un estado STARTED (se creó ahí);
--   3. su creación, si nunca cambió de estado y hoy está en un estado STARTED.
UPDATE issues i
SET started_at = LEAST(
        (SELECT min(a.created_at)
         FROM issue_activities a
         JOIN states s ON s.project_id = i.project_id AND s.state_group = 'STARTED'
             AND lower(s.name) = lower(a.new_value)
         WHERE a.issue_id = i.id AND a.field = 'STATE'),
        (SELECT i.created_at
         FROM (SELECT a.old_value
               FROM issue_activities a
               WHERE a.issue_id = i.id AND a.field = 'STATE'
               ORDER BY a.created_at, a.id
               LIMIT 1) first_move
         JOIN states s ON s.project_id = i.project_id AND s.state_group = 'STARTED'
             AND lower(s.name) = lower(first_move.old_value)),
        (SELECT i.created_at
         FROM states s
         WHERE s.id = i.state_id AND s.state_group = 'STARTED'
           AND NOT EXISTS (SELECT 1 FROM issue_activities a WHERE a.issue_id = i.id AND a.field = 'STATE')));

-- Las que hoy están en un estado STARTED y el historial no explica (por ejemplo, un estado renombrado): la fecha de
-- su último cambio de estado o, si no hay, su creación. Las demás que nunca pasaron por STARTED quedan en NULL.
UPDATE issues i
SET started_at = COALESCE(
        (SELECT max(a.created_at) FROM issue_activities a WHERE a.issue_id = i.id AND a.field = 'STATE'),
        i.created_at)
FROM states s
WHERE s.id = i.state_id
  AND s.state_group = 'STARTED'
  AND i.started_at IS NULL;

-- La daily cuenta las tareas que empezaron en un periodo
CREATE INDEX idx_issues_started ON issues (project_id, started_at) WHERE started_at IS NOT NULL;
