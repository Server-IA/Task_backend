-- Quién marcó cada criterio o paso de la checklist y cuándo, y la última vez que cambió la checklist de cada tarea
-- («checklist actualizado hace…»).

ALTER TABLE issue_checklist_items
    ADD COLUMN checked_by_id UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN checked_at    TIMESTAMPTZ;

CREATE INDEX idx_issue_checklist_items_checked_by ON issue_checklist_items (checked_by_id)
    WHERE checked_by_id IS NOT NULL;

-- Elementos ya marcados: no se sabe quién los marcó; la fecha aproximada es su última actualización.
UPDATE issue_checklist_items SET checked_at = updated_at WHERE done;

-- Agregar, editar, marcar, reordenar o borrar un elemento la actualiza (sin cambiar updated_at de la tarea).
ALTER TABLE issues ADD COLUMN checklist_updated_at TIMESTAMPTZ;

UPDATE issues i
SET checklist_updated_at = c.last_change
FROM (SELECT issue_id, max(updated_at) AS last_change FROM issue_checklist_items GROUP BY issue_id) c
WHERE c.issue_id = i.id;
