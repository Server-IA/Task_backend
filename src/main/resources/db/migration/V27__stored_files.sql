-- Archivos e imágenes en Cloudflare R2.
--
-- stored_files: un objeto del bucket (privado). Tipos:
--   ATTACHMENT    adjunto de una tarea (sección «Adjuntos», cuenta para el clip)
--   INLINE_IMAGE  imagen pegada en una descripción o un comentario (Markdown «![nombre](/files/<id>)»); issue_id es
--                 NULL mientras la tarea no existe (Nueva tarea) y se completa al crearla
--   AVATAR        foto de perfil (sin workspace ni proyecto: no cuenta para la cuota de ningún workspace)
-- Estados: PENDING (se firmó la subida; el navegador la está subiendo) y READY (confirmada: el backend comprobó en R2
-- su tamaño y su tipo). Las PENDING de más de 24 h se borran solas (MaintenanceJobs).
CREATE TABLE stored_files (
    id           UUID PRIMARY KEY,
    workspace_id UUID REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   UUID REFERENCES projects (id) ON DELETE CASCADE,
    issue_id     UUID REFERENCES issues (id) ON DELETE CASCADE,
    uploaded_by  UUID REFERENCES users (id) ON DELETE SET NULL,
    kind         VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    object_key   VARCHAR(200) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    confirmed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_stored_files_key UNIQUE (object_key),
    CONSTRAINT ck_stored_files_kind CHECK (kind IN ('ATTACHMENT', 'INLINE_IMAGE', 'AVATAR')),
    CONSTRAINT ck_stored_files_status CHECK (status IN ('PENDING', 'READY')),
    CONSTRAINT ck_stored_files_size CHECK (size_bytes > 0),
    -- Los archivos de tareas son de un proyecto (y su workspace); las fotos de perfil, de nadie más que su dueño
    CONSTRAINT ck_stored_files_scope CHECK (
        (kind = 'AVATAR' AND workspace_id IS NULL AND project_id IS NULL AND issue_id IS NULL)
            OR (kind <> 'AVATAR' AND workspace_id IS NOT NULL AND project_id IS NOT NULL)),
    CONSTRAINT ck_stored_files_attachment CHECK (kind <> 'ATTACHMENT' OR issue_id IS NOT NULL)
);

-- Adjuntos de una tarea (y el clip de las tarjetas)
CREATE INDEX idx_stored_files_issue ON stored_files (issue_id, created_at) WHERE issue_id IS NOT NULL;
-- Uso del workspace (cuota) y de cada proyecto
CREATE INDEX idx_stored_files_workspace ON stored_files (workspace_id, size_bytes DESC) WHERE workspace_id IS NOT NULL;
CREATE INDEX idx_stored_files_project ON stored_files (project_id) WHERE project_id IS NOT NULL;
-- Subidas sin confirmar (limpieza) y fotos de perfil de cada usuario
CREATE INDEX idx_stored_files_pending ON stored_files (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_stored_files_uploader ON stored_files (uploaded_by, kind);

-- Objetos de R2 por borrar. Cada fila borrada de stored_files (a mano, o en cascada al borrar una tarea, un proyecto o
-- un workspace) deja aquí su clave en la misma transacción: si la transacción se deshace, no se borra nada en R2, y si
-- R2 falla, la clave se queda para el siguiente intento (StorageCleanup). Así no quedan objetos huérfanos.
CREATE TABLE storage_deletions (
    id         BIGSERIAL PRIMARY KEY,
    object_key VARCHAR(200) NOT NULL,
    attempts   INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE FUNCTION queue_storage_deletion() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO storage_deletions (object_key) VALUES (OLD.object_key);
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_stored_files_deleted
    AFTER DELETE ON stored_files
    FOR EACH ROW
EXECUTE FUNCTION queue_storage_deletion();

-- Historial: «Ana adjuntó «mockup.png»» (field ATTACHMENT; new_value = nombre al adjuntar, old_value = nombre al
-- eliminar). file_id apunta al archivo mientras exista (la tarjeta del feed muestra su tamaño y su miniatura).
ALTER TABLE issue_activities
    ADD COLUMN file_id UUID REFERENCES stored_files (id) ON DELETE SET NULL;

CREATE INDEX idx_issue_activities_file ON issue_activities (file_id) WHERE file_id IS NOT NULL;

-- Aviso a los project managers: el workspace pasó el 90 % de su espacio (la notificación lleva el workspace)
ALTER TABLE notifications
    ADD COLUMN workspace_id UUID REFERENCES workspaces (id) ON DELETE CASCADE;

CREATE INDEX idx_notifications_workspace ON notifications (workspace_id) WHERE workspace_id IS NOT NULL;

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('ISSUE_ASSIGNED', 'ISSUE_COMMENTED', 'ISSUE_UNBLOCKED', 'ISSUE_MENTIONED', 'RETRO_STARTED',
                    'STORY_READY', 'STORAGE_WARNING'));
