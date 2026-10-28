-- Valores por defecto de las plantillas: el estado y los asignados con los que nace la tarea

-- NULL: la plantilla no fija estado. Si se borra el estado, la plantilla deja de fijarlo.
ALTER TABLE issue_templates ADD COLUMN state_id UUID REFERENCES states (id) ON DELETE SET NULL;

CREATE INDEX idx_issue_templates_state ON issue_templates (state_id) WHERE state_id IS NOT NULL;

-- Asignados por defecto: miembros del proyecto (al salir del proyecto o del workspace se quitan de sus plantillas)
CREATE TABLE issue_template_assignees (
    template_id UUID NOT NULL REFERENCES issue_templates (id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (template_id, user_id)
);

CREATE INDEX idx_issue_template_assignees_user ON issue_template_assignees (user_id);
