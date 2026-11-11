-- La daily ya no lleva tiempo por persona: el reloj de «Presentar» no aportaba y se quitó, así que sobran el tiempo
-- general y los tiempos propios. Los ajustes quedan en qué bloques se exponen y los puntos a tratar.

ALTER TABLE daily_settings
    DROP CONSTRAINT ck_daily_settings_seconds,
    DROP COLUMN seconds_per_person,
    DROP COLUMN person_seconds;
