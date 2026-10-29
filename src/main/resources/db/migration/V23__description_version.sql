-- Guardado de la descripción sin pisar cambios ajenos: cada cambio de la descripción sube description_version. Quien
-- edita manda la versión de la que partió (baseDescriptionVersion) y, si ya no es la actual, la API responde 409 con
-- la versión actual, quién la cambió y cuándo. Solo cuenta la descripción: cambiar el estado o la prioridad no la sube.

ALTER TABLE issues
    ADD COLUMN description_version       INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN description_updated_at    TIMESTAMPTZ,
    ADD COLUMN description_updated_by_id UUID        REFERENCES users (id) ON DELETE SET NULL;

-- Último cambio conocido: la última edición de la descripción en el historial (una sesión de edición es una sola
-- entrada; su updated_at es el último guardado)...
UPDATE issues i
SET description_updated_at    = last_edit.edited_at,
    description_updated_by_id = last_edit.actor_id
FROM (SELECT DISTINCT ON (a.issue_id) a.issue_id, a.actor_id, a.updated_at AS edited_at
      FROM issue_activities a
      WHERE a.field = 'DESCRIPTION'
      ORDER BY a.issue_id, a.updated_at DESC) last_edit
WHERE last_edit.issue_id = i.id;

-- ... y si nunca se editó, la descripción es la que se escribió al crear la tarea.
UPDATE issues
SET description_updated_at    = created_at,
    description_updated_by_id = created_by_id
WHERE description IS NOT NULL
  AND description_updated_at IS NULL;
