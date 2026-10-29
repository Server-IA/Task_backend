-- Ajustes de la segunda revisión del backend.

-- 1. Versión de las credenciales: cada cambio o restablecimiento de la contraseña la incrementa y los access tokens
--    llevan la suya (claim «cv»); solo vale un token con la versión actual. Reemplaza la comparación del «iat» (al
--    segundo) con credentials_changed_at, que se sigue guardando como dato. Las cuentas que ya cambiaron su
--    contraseña empiezan en 1: sus tokens anteriores (sin «cv», que cuenta como 0) dejan de valer.
ALTER TABLE users
    ADD COLUMN credentials_version INTEGER NOT NULL DEFAULT 0;

UPDATE users
SET credentials_version = 1
WHERE credentials_changed_at IS NOT NULL;

-- 2. Foto del cierre de los ciclos cerrados antes de V25 (closed_* en NULL): se calcula una vez con las tareas que
--    siguen en el ciclo, igual que el resumen de la retrospectiva hacía en vivo, y desde ahora queda fija (pasar una
--    de sus tareas a otro ciclo ya no cambia su resumen).
--    Trabajo = tareas y bugs (no historias) que no están en un estado cancelado; completadas = las de un estado
--    completado; tiempo de ciclo = mediana, en días con un decimal, de started_at a completed_at de las completadas.
WITH work AS (
    SELECT ci.cycle_id,
           st.state_group,
           CASE
               WHEN st.state_group = 'COMPLETED' AND i.started_at IS NOT NULL AND i.completed_at IS NOT NULL
                   AND i.completed_at >= i.started_at
                   THEN floor(EXTRACT(EPOCH FROM (i.completed_at - i.started_at)) * 1000) / 86400000.0
               END AS cycle_time_days
    FROM cycle_issues ci
             JOIN cycles c ON c.id = ci.cycle_id
             JOIN issues i ON i.id = ci.issue_id
             JOIN states st ON st.id = i.state_id
    WHERE c.closed_at IS NOT NULL
      AND c.closed_completed_count IS NULL
      AND i.issue_type <> 'STORY'
      AND st.state_group <> 'CANCELLED'
),
     summary AS (
         SELECT cycle_id,
                count(*) FILTER (WHERE state_group = 'COMPLETED')                            AS completed,
                count(*)                                                                     AS total,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY cycle_time_days)                 AS median_days
         FROM work
         GROUP BY cycle_id
     )
UPDATE cycles c
SET closed_completed_count = coalesce(s.completed, 0),
    closed_total_count     = coalesce(s.total, 0) + c.carried_to_cycle + c.carried_to_backlog,
    closed_cycle_time_days = CASE
                                 WHEN s.median_days IS NULL THEN NULL
                                 ELSE round((s.median_days * 10)::numeric) / 10
                             END
FROM cycles target
         LEFT JOIN summary s ON s.cycle_id = target.id
WHERE c.id = target.id
  AND c.closed_at IS NOT NULL
  AND c.closed_completed_count IS NULL;

-- 3. Paginación por cursor de la bandeja (created_at, id), con o sin filtro por tipo.
CREATE INDEX idx_notifications_recipient_cursor ON notifications (recipient_id, created_at DESC, id DESC);
