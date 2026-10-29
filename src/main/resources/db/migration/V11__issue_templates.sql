-- Plantillas de tarea por proyecto (las configura el project manager)

CREATE TABLE issue_templates (
    id           UUID PRIMARY KEY,
    project_id   UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name         VARCHAR(60) NOT NULL,
    -- Se antepone al título, por ejemplo '[Bug] ' (con su espacio final)
    title_prefix VARCHAR(40),
    -- Esqueleto en markdown de la descripción
    description  TEXT,
    -- NULL: la plantilla no fija prioridad
    priority     VARCHAR(10),
    position     INTEGER     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_issue_templates_priority CHECK (priority IN ('URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE'))
);

-- El nombre no se repite dentro del proyecto (sin importar mayúsculas)
CREATE UNIQUE INDEX uk_issue_templates_name ON issue_templates (project_id, lower(name));

CREATE TABLE issue_template_labels (
    template_id UUID NOT NULL REFERENCES issue_templates (id) ON DELETE CASCADE,
    label_id    UUID NOT NULL REFERENCES labels (id) ON DELETE CASCADE,
    PRIMARY KEY (template_id, label_id)
);

CREATE INDEX idx_issue_template_labels_label ON issue_template_labels (label_id);

-- Criterios y pasos que la plantilla agrega a la tarea, en orden
CREATE TABLE issue_template_items (
    template_id UUID         NOT NULL REFERENCES issue_templates (id) ON DELETE CASCADE,
    position    INTEGER      NOT NULL,
    kind        VARCHAR(10)  NOT NULL,
    text        VARCHAR(500) NOT NULL,
    PRIMARY KEY (template_id, position),
    CONSTRAINT ck_issue_template_items_kind CHECK (kind IN ('CRITERION', 'CHECK'))
);

-- Plantillas por defecto de los proyectos que ya existen (los nuevos las reciben al crearse; mismos textos que
-- DefaultTemplates.java)
INSERT INTO issue_templates (id, project_id, name, title_prefix, description, priority, position)
SELECT gen_random_uuid(), p.id, t.name, t.title_prefix, t.description, t.priority, t.position
FROM projects p
CROSS JOIN (VALUES
    ('Reporte de bug', '[Bug] ',
     E'## Pasos para reproducir\n1. …\n2. …\n\n## Resultado esperado\n…\n\n## Resultado actual\n…\n\n## Entorno\n- Navegador: …\n- Sistema operativo: …\n- Versión: …',
     'HIGH', 0),
    ('Historia de usuario', NULL, 'Como … quiero … para …', NULL, 1),
    ('Tarea técnica', NULL, E'## Objetivo\n…\n\n## Detalles técnicos\n…', NULL, 2)
) AS t (name, title_prefix, description, priority, position);

INSERT INTO issue_template_items (template_id, position, kind, text)
SELECT it.id, i.position, i.kind, i.text
FROM issue_templates it
JOIN (VALUES
    ('Reporte de bug', 0, 'CRITERION', 'Dado los pasos, cuando se repiten, entonces el error ya no ocurre'),
    ('Reporte de bug', 1, 'CHECK', 'Reproducir el error'),
    ('Reporte de bug', 2, 'CHECK', 'Escribir la prueba'),
    ('Reporte de bug', 3, 'CHECK', 'Corregir'),
    ('Reporte de bug', 4, 'CHECK', 'Probar en otro navegador'),
    ('Historia de usuario', 0, 'CRITERION',
     'Dado que soy un usuario registrado, cuando inicio sesión, entonces veo mi panel principal'),
    ('Historia de usuario', 1, 'CRITERION',
     'Dado que dejo un campo obligatorio vacío, cuando envío el formulario, entonces veo un mensaje de error'),
    ('Tarea técnica', 0, 'CHECK', 'Implementar'),
    ('Tarea técnica', 1, 'CHECK', 'Pruebas'),
    ('Tarea técnica', 2, 'CHECK', 'Revisión de código')
) AS i (template_name, position, kind, text) ON i.template_name = it.name;
