-- Fecha de completado de las tareas e índices para estadísticas, "mi trabajo" y el feed de actividad

ALTER TABLE issues ADD COLUMN completed_at TIMESTAMPTZ;

-- Tareas que ya están completadas: se usa la fecha del último cambio de estado si llevó a la tarea a su
-- estado actual (el historial guarda el nombre del estado); si no se puede saber, la última actualización.
UPDATE issues i
SET completed_at = COALESCE(
        (SELECT last_move.created_at
         FROM (SELECT a.created_at, a.new_value
               FROM issue_activities a
               WHERE a.issue_id = i.id AND a.field = 'STATE'
               ORDER BY a.created_at DESC
               LIMIT 1) last_move
         WHERE last_move.new_value = s.name),
        i.updated_at)
FROM states s
WHERE s.id = i.state_id
  AND s.state_group = 'COMPLETED';

CREATE INDEX idx_issues_completed ON issues (project_id, completed_at) WHERE completed_at IS NOT NULL;
CREATE INDEX idx_issues_created_by ON issues (created_by_id);
CREATE INDEX idx_comments_author ON comments (author_id);
CREATE INDEX idx_issue_activities_created ON issue_activities (created_at);
