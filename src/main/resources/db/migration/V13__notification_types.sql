-- Nuevos tipos de notificación: tarea desbloqueada (se completó su último bloqueo abierto) y mención en un
-- comentario

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('ISSUE_ASSIGNED', 'ISSUE_COMMENTED', 'ISSUE_UNBLOCKED', 'ISSUE_MENTIONED'));
