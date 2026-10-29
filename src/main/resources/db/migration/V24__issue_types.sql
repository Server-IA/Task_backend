-- Tipos de tarea: historia (STORY), tarea (TASK) y bug (BUG). Una historia se parte en tareas (sus hijas, con
-- parent_id) y avanza sola con ellas; los bugs hijos de una historia son los «bugs ligados» (no cuentan en su
-- progreso, pero uno abierto la frena antes de «lista para revisar»).
--
-- La columna se llama issue_type (TYPE es una palabra que conviene no usar como nombre de columna).

ALTER TABLE issues
    ADD COLUMN issue_type      VARCHAR(10) NOT NULL DEFAULT 'TASK',
    -- Cuándo y quién cerró la historia (la pasó a un estado COMPLETED o CANCELLED); NULL mientras esté abierta.
    -- «N bugs después de cerrar» cuenta los bugs hijos creados después de closed_at.
    ADD COLUMN closed_at       TIMESTAMPTZ,
    ADD COLUMN closed_by_id    UUID REFERENCES users (id) ON DELETE SET NULL,
    -- Ya se avisó a los project managers de que la historia está «lista para revisar» (el aviso sale una vez cada
    -- vez que pasa a estarlo; se vuelve a FALSE cuando deja de estarlo).
    ADD COLUMN review_notified BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_issues_type CHECK (issue_type IN ('STORY', 'TASK', 'BUG'));

-- Las que ya existen son tareas, salvo:
--   * un título que empieza con «[Bug]» (el prefijo de la plantilla «Reporte de bug») -> BUG
--   * una descripción con la cita de la historia de usuario («> **Como** …», la de la plantilla) -> STORY
UPDATE issues
SET issue_type = 'BUG'
WHERE title ILIKE '[bug]%';

UPDATE issues
SET issue_type = 'STORY'
WHERE issue_type = 'TASK'
  AND description ~ '(^|\n)>[ ]*\*\*Como\*\*';

-- Historias ya cerradas: cuándo (al completarse, o su última actualización) y quién (el último cambio de estado
-- hacia el estado actual, si está en el historial).
UPDATE issues i
SET closed_at = COALESCE(i.completed_at, i.updated_at)
FROM states s
WHERE s.id = i.state_id
  AND i.issue_type = 'STORY'
  AND s.state_group IN ('COMPLETED', 'CANCELLED');

UPDATE issues i
SET closed_by_id = last_change.actor_id
FROM (SELECT DISTINCT ON (a.issue_id) a.issue_id, a.actor_id, a.new_value
      FROM issue_activities a
      WHERE a.field = 'STATE'
      ORDER BY a.issue_id, a.created_at DESC) last_change,
     states s
WHERE last_change.issue_id = i.id
  AND s.id = i.state_id
  AND last_change.new_value = s.name
  AND i.closed_at IS NOT NULL;

-- Historias que hoy ya están «listas para revisar»: cuentan como avisadas (no se avisa de golpe al migrar).
UPDATE issues st
SET review_notified = TRUE
FROM states own
WHERE own.id = st.state_id
  AND st.issue_type = 'STORY'
  AND own.state_group NOT IN ('COMPLETED', 'CANCELLED')
  AND EXISTS (SELECT 1 FROM issues c WHERE c.parent_id = st.id AND c.issue_type = 'TASK')
  AND NOT EXISTS (SELECT 1
                  FROM issues c
                           JOIN states cs ON cs.id = c.state_id
                  WHERE c.parent_id = st.id
                    AND c.issue_type IN ('TASK', 'BUG')
                    AND cs.state_group NOT IN ('COMPLETED', 'CANCELLED'));

-- Listado por tipo (tablero y lista filtran por tipo; «Historias» lista las del proyecto)
CREATE INDEX idx_issues_project_type ON issues (project_id, issue_type);

-- Plantillas: el tipo de la tarea que crean
ALTER TABLE issue_templates
    ADD COLUMN issue_type VARCHAR(10) NOT NULL DEFAULT 'TASK',
    ADD CONSTRAINT ck_issue_templates_type CHECK (issue_type IN ('STORY', 'TASK', 'BUG'));

UPDATE issue_templates
SET issue_type = 'BUG'
WHERE name = 'Reporte de bug'
   OR title_prefix ILIKE '[bug]%';

UPDATE issue_templates
SET issue_type = 'STORY'
WHERE issue_type = 'TASK'
  AND (name = 'Historia de usuario' OR description ~ '(^|\n)>[ ]*\*\*Como\*\*');

-- Nuevo aviso: la historia está lista para revisar (lo manda TaskFlow, sin autor)
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('ISSUE_ASSIGNED', 'ISSUE_COMMENTED', 'ISSUE_UNBLOCKED', 'ISSUE_MENTIONED', 'RETRO_STARTED',
                    'STORY_READY'));
