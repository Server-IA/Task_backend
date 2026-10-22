-- Usuarios y autenticación

CREATE TABLE users (
    id             UUID PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    full_name      VARCHAR(120) NOT NULL,
    avatar_url     VARCHAR(500),
    email_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE verification_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    type       VARCHAR(30) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_verification_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_verification_tokens_type CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);

CREATE INDEX idx_verification_tokens_user ON verification_tokens (user_id, type);
