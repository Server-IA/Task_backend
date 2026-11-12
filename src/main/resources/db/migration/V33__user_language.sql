-- Idioma de la cuenta: en el que la persona ve la aplicación y recibe los correos. NULL = nunca lo eligió (se usa
-- el de cada petición, Accept-Language).

ALTER TABLE users
    ADD COLUMN language VARCHAR(5),
    ADD CONSTRAINT ck_users_language CHECK (language IN ('es', 'en'));
