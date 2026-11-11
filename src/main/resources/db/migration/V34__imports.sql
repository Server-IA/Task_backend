-- Importar desde otras herramientas (Jira, Trello, Linear, Asana o un archivo CSV).
--
-- import_jobs: cada importación, desde que se conecta el origen hasta que termina (o se deshace). Lo que se va
-- decidiendo en el recorrido (qué proyectos, las equivalencias) y lo que resulta (avance, informe) se guarda como JSON
-- en texto: solo lo lee y lo escribe el backend, entero.
-- import_payloads: el archivo subido y lo leído del origen ya en el formato común, mientras dura la importación.
-- import_items: lo que creó cada importación, para poder deshacerla completa sin tocar nada más.
-- import_pending_assignments: tareas de personas a las que se invitó desde la importación; se les asignan cuando
-- aceptan. Van por correo y no por invitación, porque una invitación se reemplaza al reenviarla.

CREATE TABLE import_jobs (
    id            UUID PRIMARY KEY,
    workspace_id  UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    created_by_id UUID         REFERENCES users (id) ON DELETE SET NULL,
    source        VARCHAR(20)  NOT NULL,
    via           VARCHAR(10)  NOT NULL,
    source_label  VARCHAR(200) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    discovery     TEXT,
    selection     TEXT,
    mapping       TEXT,
    progress      TEXT,
    report        TEXT,
    error         VARCHAR(500),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_import_jobs_source CHECK (source IN ('JIRA', 'TRELLO', 'LINEAR', 'ASANA', 'CSV')),
    CONSTRAINT ck_import_jobs_via CHECK (via IN ('API', 'FILE')),
    CONSTRAINT ck_import_jobs_status CHECK (status IN ('DRAFT', 'FETCHING', 'READY', 'RUNNING', 'COMPLETED', 'FAILED',
                                                       'STOPPED', 'UNDOING', 'UNDONE'))
);

CREATE INDEX idx_import_jobs_workspace ON import_jobs (workspace_id, created_at DESC);

CREATE TABLE import_payloads (
    job_id UUID        NOT NULL REFERENCES import_jobs (id) ON DELETE CASCADE,
    kind   VARCHAR(10) NOT NULL,
    data   BYTEA       NOT NULL,
    PRIMARY KEY (job_id, kind),
    CONSTRAINT ck_import_payloads_kind CHECK (kind IN ('UPLOAD', 'BUNDLE'))
);

CREATE TABLE import_items (
    id        BIGSERIAL PRIMARY KEY,
    job_id    UUID        NOT NULL REFERENCES import_jobs (id) ON DELETE CASCADE,
    kind      VARCHAR(20) NOT NULL,
    entity_id UUID        NOT NULL,
    CONSTRAINT ck_import_items_kind CHECK (kind IN ('PROJECT', 'STATE', 'LABEL', 'MODULE', 'CYCLE', 'ISSUE'))
);

CREATE INDEX idx_import_items_job ON import_items (job_id, kind);

CREATE TABLE import_pending_assignments (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    email        VARCHAR(255) NOT NULL,
    issue_id     UUID         NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    CONSTRAINT uk_import_pending_assignments UNIQUE (issue_id, email)
);

CREATE INDEX idx_import_pending_assignments_email ON import_pending_assignments (workspace_id, email);

-- De dónde vino una tarea importada: la herramienta, su código allá y el enlace para abrirla.
ALTER TABLE issues
    ADD COLUMN import_source VARCHAR(20),
    ADD COLUMN import_key    VARCHAR(100),
    ADD COLUMN import_url    VARCHAR(1000);

-- Aviso en la Bandeja cuando una importación termina.
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('ISSUE_ASSIGNED', 'ISSUE_COMMENTED', 'ISSUE_UNBLOCKED', 'ISSUE_MENTIONED', 'RETRO_STARTED',
                    'STORY_READY', 'STORAGE_WARNING', 'IMPORT_FINISHED'));
