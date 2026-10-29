-- Ajustes de la revisión del backend.

-- 1. Una historia no puede ser hija de otra tarea. V24 clasificó como historia algunas sub-tareas (por la cita
--    «> **Como** …» de su descripción) sin mirar su padre; se sueltan de él para que se puedan volver a editar.
UPDATE issues
SET parent_id = NULL
WHERE issue_type = 'STORY'
  AND parent_id IS NOT NULL;

-- 2. Cambiar o restablecer la contraseña invalida los access tokens emitidos antes (se compara con su «iat»).
ALTER TABLE users
    ADD COLUMN credentials_changed_at TIMESTAMPTZ;

-- 3. Foto del ciclo al cerrarlo (lo que muestra el resumen de la retrospectiva): no cambia aunque después una tarea
--    del ciclo se reabra o pase a otro ciclo. NULL en los ciclos cerrados antes de esta versión (el resumen se
--    calcula con las tareas que siguen en el ciclo, como antes).
ALTER TABLE cycles
    ADD COLUMN closed_completed_count INTEGER,
    ADD COLUMN closed_total_count     INTEGER,
    ADD COLUMN closed_cycle_time_days DOUBLE PRECISION;

-- 4. Índices: el borrado en cascada de una tarea (o de un usuario) recorría toda la tabla de notificaciones, y el
--    final de una columna del tablero (findMaxSortOrder, por estado) no podía usar idx_issues_board.
CREATE INDEX idx_notifications_issue ON notifications (issue_id) WHERE issue_id IS NOT NULL;
CREATE INDEX idx_notifications_actor ON notifications (actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_issues_state_sort ON issues (state_id, sort_order);

-- 5. Limpieza programada (MaintenanceJobs): tokens vencidos y notificaciones leídas antiguas.
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
CREATE INDEX idx_verification_tokens_expires ON verification_tokens (expires_at);
CREATE INDEX idx_notifications_read_created ON notifications (created_at) WHERE is_read;
