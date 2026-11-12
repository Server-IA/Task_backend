-- Integración con GitHub: el workspace instala la app de TaskFlow en su organización (o cuenta) y cada proyecto dice
-- qué repositorios son suyos. Lo que pasa en GitHub llega por avisos (webhooks) y se guarda aquí; TaskFlow no guarda
-- ningún token (los pide a GitHub con la clave de la app cada vez que hacen falta).
--
-- github_installations: dónde está instalada la app, una por workspace.
-- github_repositories: los repositorios que esa instalación deja ver.
-- project_repositories: qué repositorios son de cada proyecto.
-- github_branches / github_pull_requests: ramas y pull requests, enlazados a la tarea que nombran (WEB-22 en la rama,
--   en el título del PR o en su descripción). Un PR sin tarea también se guarda: se ve en el proyecto y se puede
--   enlazar a mano.
-- github_workflow_runs: ejecuciones de GitHub Actions. Las que pide TaskFlow (a mano o por una regla) nacen aquí sin
--   run_id y se completan cuando GitHub avisa de la ejecución.
-- github_project_settings: a qué estado pasa la tarea cuando se crea su rama, se abre, se fusiona o se cierra su PR
--   (NULL = esa automatización está apagada).
-- github_rules: «cuando pase esto en una tarea, ejecutar este workflow».
-- github_webhook_deliveries: avisos ya atendidos (GitHub puede repetir uno).

CREATE TABLE github_installations (
    id              UUID PRIMARY KEY,
    workspace_id    UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    installation_id BIGINT       NOT NULL,
    account_login   VARCHAR(100) NOT NULL,
    account_type    VARCHAR(20)  NOT NULL,
    installed_by_id UUID         REFERENCES users (id) ON DELETE SET NULL,
    last_event_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_github_installations_workspace UNIQUE (workspace_id),
    CONSTRAINT uk_github_installations_installation UNIQUE (installation_id)
);

CREATE TABLE github_repositories (
    id              UUID PRIMARY KEY,
    installation_id UUID         NOT NULL REFERENCES github_installations (id) ON DELETE CASCADE,
    github_id       BIGINT       NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    default_branch  VARCHAR(255),
    is_private      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_github_repositories UNIQUE (installation_id, github_id)
);

CREATE TABLE project_repositories (
    project_id    UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES github_repositories (id) ON DELETE CASCADE,
    PRIMARY KEY (project_id, repository_id)
);

CREATE INDEX idx_project_repositories_repository ON project_repositories (repository_id);

CREATE TABLE github_branches (
    id            UUID PRIMARY KEY,
    repository_id UUID         NOT NULL REFERENCES github_repositories (id) ON DELETE CASCADE,
    issue_id      UUID         NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_github_branches UNIQUE (repository_id, name, issue_id)
);

CREATE INDEX idx_github_branches_issue ON github_branches (issue_id);

CREATE TABLE github_pull_requests (
    id            UUID PRIMARY KEY,
    repository_id UUID         NOT NULL REFERENCES github_repositories (id) ON DELETE CASCADE,
    issue_id      UUID         REFERENCES issues (id) ON DELETE SET NULL,
    number        INT          NOT NULL,
    title         VARCHAR(500) NOT NULL,
    state         VARCHAR(10)  NOT NULL,
    url           VARCHAR(500) NOT NULL,
    author_login  VARCHAR(100),
    head_branch   VARCHAR(255),
    base_branch   VARCHAR(255),
    head_sha      VARCHAR(64),
    reviewers     VARCHAR(500),
    checks_state  VARCHAR(10),
    checks_total  INT          NOT NULL DEFAULT 0,
    checks_failed INT          NOT NULL DEFAULT 0,
    merged_at     TIMESTAMPTZ,
    opened_at     TIMESTAMPTZ,
    changed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_github_pull_requests UNIQUE (repository_id, number),
    CONSTRAINT ck_github_pull_requests_state CHECK (state IN ('OPEN', 'DRAFT', 'MERGED', 'CLOSED')),
    CONSTRAINT ck_github_pull_requests_checks CHECK (checks_state IS NULL OR checks_state IN ('SUCCESS', 'FAILURE', 'PENDING'))
);

CREATE INDEX idx_github_pull_requests_issue ON github_pull_requests (issue_id);

CREATE TABLE github_rules (
    id            UUID PRIMARY KEY,
    project_id    UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    fires_on      VARCHAR(20)  NOT NULL,
    repository_id UUID         NOT NULL REFERENCES github_repositories (id) ON DELETE CASCADE,
    workflow_id   BIGINT       NOT NULL,
    workflow_name VARCHAR(200) NOT NULL,
    ref           VARCHAR(255) NOT NULL,
    created_by_id UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_github_rules_fires_on CHECK (fires_on IN ('PR_MERGED', 'ISSUE_COMPLETED'))
);

CREATE INDEX idx_github_rules_project ON github_rules (project_id);

CREATE TABLE github_workflow_runs (
    id              UUID PRIMARY KEY,
    repository_id   UUID         NOT NULL REFERENCES github_repositories (id) ON DELETE CASCADE,
    run_id          BIGINT,
    workflow_id     BIGINT,
    workflow_name   VARCHAR(200),
    title           VARCHAR(500),
    branch          VARCHAR(255),
    head_sha        VARCHAR(64),
    event           VARCHAR(40),
    status          VARCHAR(20)  NOT NULL,
    conclusion      VARCHAR(20),
    actor_login     VARCHAR(100),
    url             VARCHAR(500),
    pr_number       INT,
    issue_id        UUID         REFERENCES issues (id) ON DELETE SET NULL,
    rule_id         UUID         REFERENCES github_rules (id) ON DELETE SET NULL,
    triggered_by_id UUID         REFERENCES users (id) ON DELETE SET NULL,
    requested_at    TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_github_workflow_runs UNIQUE (repository_id, run_id)
);

CREATE INDEX idx_github_workflow_runs_repository ON github_workflow_runs (repository_id, changed_at DESC);
CREATE INDEX idx_github_workflow_runs_issue ON github_workflow_runs (issue_id);

CREATE TABLE github_project_settings (
    project_id            UUID PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    on_branch_state_id    UUID REFERENCES states (id) ON DELETE SET NULL,
    on_pr_opened_state_id UUID REFERENCES states (id) ON DELETE SET NULL,
    on_pr_merged_state_id UUID REFERENCES states (id) ON DELETE SET NULL,
    on_pr_closed_state_id UUID REFERENCES states (id) ON DELETE SET NULL,
    branch_format         VARCHAR(20) NOT NULL DEFAULT 'CODE_TITLE',
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_github_project_settings_branch CHECK (branch_format IN ('CODE_TITLE', 'TYPE_CODE_TITLE', 'CODE'))
);

CREATE TABLE github_webhook_deliveries (
    delivery_id VARCHAR(80) PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Aviso en la Bandeja cuando el pull request de una tarea se fusiona y la tarea no se pudo completar sola porque le
-- faltan criterios de aceptación.
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('ISSUE_ASSIGNED', 'ISSUE_COMMENTED', 'ISSUE_UNBLOCKED', 'ISSUE_MENTIONED', 'RETRO_STARTED',
                    'STORY_READY', 'STORAGE_WARNING', 'IMPORT_FINISHED', 'PR_MERGED_PENDING'));
