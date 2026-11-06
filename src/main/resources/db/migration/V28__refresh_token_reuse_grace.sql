-- Margen de reutilización de los refresh tokens rotados.
--
-- Dos recargas seguidas de la página: la primera rota el token, pero el navegador recarga antes de guardar el nuevo y
-- la segunda vuelve a mandar el viejo. Antes eso contaba como robo y cerraba todas las sesiones. Ahora, si el token se
-- rotó hace poco (app.tokens.refresh-reuse-grace-seconds) y su sucesor sigue vivo, se rota el sucesor y se emite otro.
--
-- revoked_at   cuándo se revocó (NULL en los revocados antes de esta migración: sin margen)
-- replaced_by  el token que lo reemplazó al rotarlo. NULL si se revocó por otra causa (reutilización detectada): sin
--              margen. Sin FK a propósito: si el sucesor se borra (logout, limpieza de vencidos) la cadena se corta y
--              el token viejo tampoco tiene margen; así borrar tokens no tiene que buscar quién los referencia.
ALTER TABLE refresh_tokens ADD COLUMN revoked_at  TIMESTAMPTZ;
ALTER TABLE refresh_tokens ADD COLUMN replaced_by UUID;
