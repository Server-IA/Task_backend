-- La cuenta de GitHub de cada persona. Lo que alguien hace en GitHub desde TaskFlow (crear una rama, ejecutar un
-- workflow) se hace con su cuenta: sale a su nombre y solo donde ella tiene acceso. Sin cuenta enlazada se puede ver,
-- pero no actuar.
--
-- Los tokens se guardan cifrados (AES-GCM). El de acceso dura horas y se renueva con el de refresco; si GitHub ya no
-- acepta ninguno, la cuenta se desenlaza y la persona la vuelve a conectar.
-- Una cuenta de GitHub pertenece a una sola persona de TaskFlow.

CREATE TABLE github_accounts (
    user_id            UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    github_id          BIGINT       NOT NULL,
    login              VARCHAR(100) NOT NULL,
    avatar_url         VARCHAR(500),
    access_token       TEXT         NOT NULL,
    refresh_token      TEXT,
    access_expires_at  TIMESTAMPTZ,
    refresh_expires_at TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_github_accounts_github UNIQUE (github_id)
);

-- Para reconocer a la persona de TaskFlow que abrió un pull request
CREATE INDEX idx_github_accounts_login ON github_accounts (lower(login));
