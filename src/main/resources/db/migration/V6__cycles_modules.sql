-- Ciclos (sprints) y módulos

CREATE TABLE cycles (
    id          UUID PRIMARY KEY,
    project_id  UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name        VARCHAR(80) NOT NULL,
    description TEXT,
    start_date  DATE,
    end_date    DATE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cycles_dates CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_cycles_project ON cycles (project_id);

-- Un issue solo puede estar en un ciclo a la vez
CREATE TABLE cycle_issues (
    cycle_id UUID NOT NULL REFERENCES cycles (id) ON DELETE CASCADE,
    issue_id UUID NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    PRIMARY KEY (cycle_id, issue_id),
    CONSTRAINT uk_cycle_issues_issue UNIQUE (issue_id)
);

CREATE TABLE modules (
    id          UUID PRIMARY KEY,
    project_id  UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name        VARCHAR(80) NOT NULL,
    description TEXT,
    start_date  DATE,
    target_date DATE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_modules_dates CHECK (start_date IS NULL OR target_date IS NULL OR target_date >= start_date)
);

CREATE INDEX idx_modules_project ON modules (project_id);

-- Un issue puede estar en varios módulos
CREATE TABLE module_issues (
    module_id UUID NOT NULL REFERENCES modules (id) ON DELETE CASCADE,
    issue_id  UUID NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    PRIMARY KEY (module_id, issue_id)
);

CREATE INDEX idx_module_issues_issue ON module_issues (issue_id);
