-- Veces que se usó cada plantilla al crear una tarea (lo incrementa POST /issues con templateId). Las tareas creadas
-- antes no guardaban la plantilla, así que se empieza en 0.
ALTER TABLE issue_templates ADD COLUMN usage_count INTEGER NOT NULL DEFAULT 0;

-- Responsable de una acción de la retrospectiva; al convertirla en tarea, la tarea se le asigna.
ALTER TABLE retro_notes ADD COLUMN owner_id UUID REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_retro_notes_owner ON retro_notes (owner_id) WHERE owner_id IS NOT NULL;
