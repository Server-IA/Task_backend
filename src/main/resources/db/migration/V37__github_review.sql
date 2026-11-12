-- Correcciones de la revisión de la integración con GitHub.

-- Un pull request que alguien dejó sin tarea a mano no se vuelve a enlazar solo, aunque su título siga nombrando la
-- tarea (antes el siguiente aviso lo re-enlazaba y repetía las automatizaciones y las reglas).
ALTER TABLE github_pull_requests
    ADD COLUMN issue_unlinked BOOLEAN NOT NULL DEFAULT FALSE;

-- Los checks de un commit se buscan por repositorio y commit en cada aviso de GitHub.
CREATE INDEX idx_github_pull_requests_sha ON github_pull_requests (repository_id, head_sha);

-- La lista de pull requests de un proyecto sale ordenada por cuándo cambiaron.
CREATE INDEX idx_github_pull_requests_changed ON github_pull_requests (repository_id, changed_at DESC);

-- La última ejecución de cada regla (y dejar las ejecuciones sin regla al borrarla).
CREATE INDEX idx_github_workflow_runs_rule ON github_workflow_runs (rule_id) WHERE rule_id IS NOT NULL;
