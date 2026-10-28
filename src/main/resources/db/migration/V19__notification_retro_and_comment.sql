-- Notificaciones:
--   RETRO_STARTED: se abrió la retrospectiva de un ciclo (cycle_id; no tiene tarea)
--   comment_id: comentario que generó un ISSUE_COMMENTED o ISSUE_MENTIONED (la bandeja muestra un extracto)

ALTER TABLE notifications
    ADD COLUMN cycle_id   UUID REFERENCES cycles (id) ON DELETE CASCADE,
    ADD COLUMN comment_id UUID REFERENCES comments (id) ON DELETE SET NULL;

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('ISSUE_ASSIGNED', 'ISSUE_COMMENTED', 'ISSUE_UNBLOCKED', 'ISSUE_MENTIONED', 'RETRO_STARTED'));

CREATE INDEX idx_notifications_cycle ON notifications (cycle_id) WHERE cycle_id IS NOT NULL;
CREATE INDEX idx_notifications_comment ON notifications (comment_id) WHERE comment_id IS NOT NULL;
-- Pestaña «Menciones» de la bandeja
CREATE INDEX idx_notifications_recipient_type ON notifications (recipient_id, type, created_at DESC);

-- Notificaciones de comentarios ya enviadas: el comentario más reciente de quien la generó en esa tarea, escrito
-- hasta el momento de la notificación (se guardan en la misma transacción).
UPDATE notifications n
SET comment_id = (SELECT c.id
                  FROM comments c
                  WHERE c.issue_id = n.issue_id
                    AND c.author_id = n.actor_id
                    AND c.created_at <= n.created_at
                  ORDER BY c.created_at DESC
                  LIMIT 1)
WHERE n.type IN ('ISSUE_COMMENTED', 'ISSUE_MENTIONED')
  AND n.issue_id IS NOT NULL
  AND n.actor_id IS NOT NULL;
