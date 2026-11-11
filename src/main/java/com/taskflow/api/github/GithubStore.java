package com.taskflow.api.github;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Las tablas de la integración con GitHub. Son datos que llegan de GitHub y se muestran tal cual (no entidades con
 * reglas propias), así que se leen y escriben con SQL. Todo corre en la transacción de quien llama.
 */
@Component
@RequiredArgsConstructor
public class GithubStore {

    private final JdbcTemplate jdbc;

    public record Installation(UUID id, UUID workspaceId, long installationId, String accountLogin, String accountType,
                               String installedByName, Instant lastEventAt, Instant createdAt) {
    }

    public record Repository(UUID id, UUID installationRowId, long installationId, UUID workspaceId, long githubId, String fullName,
                             String defaultBranch, boolean isPrivate) {
    }

    /** Un proyecto enlazado a un repositorio. */
    public record LinkedProject(UUID repositoryId, UUID projectId, String identifier, String name, String color) {
    }

    /**
     * @param issueCode       el código de la tarea enlazada («WEB-22»), si tiene
     * @param issueStateGroup el grupo de su estado
     */
    public record PullRequest(UUID id, UUID repositoryId, String repository, UUID issueId, String issueCode, String issueTitle,
                              String issueState, String issueStateGroup, UUID issueProjectId, int number, String title, String state,
                              String url, String authorLogin, String headBranch, String baseBranch, String headSha, String reviewers,
                              String checksState, int checksTotal, int checksFailed, Instant mergedAt, Instant openedAt,
                              Instant changedAt, UUID authorUserId, String authorName) {
    }

    public record Branch(UUID id, UUID repositoryId, String repository, UUID issueId, String issueCode, String issueTitle, String name,
                         Instant createdAt) {
    }

    public record Run(UUID id, UUID repositoryId, String repository, Long runId, Long workflowId, String workflowName, String title,
                      String branch, String headSha, String event, String status, String conclusion, String actorLogin, String url,
                      Integer prNumber, UUID issueId, String issueCode, UUID ruleId, String triggeredByName, Instant requestedAt,
                      Instant startedAt, Instant changedAt) {
    }

    /**
     * @param branchFormat cómo nombra TaskFlow las ramas del proyecto ({@link GithubText#BRANCH_FORMATS})
     */
    public record Settings(UUID onBranchStateId, UUID onPrOpenedStateId, UUID onPrMergedStateId, UUID onPrClosedStateId,
                           String branchFormat) {
        public static final Settings NONE = new Settings(null, null, null, null, GithubText.CODE_TITLE);

        public Settings {
            branchFormat = branchFormat == null || !GithubText.BRANCH_FORMATS.contains(branchFormat) ? GithubText.CODE_TITLE : branchFormat;
        }

        public Settings(UUID onBranchStateId, UUID onPrOpenedStateId, UUID onPrMergedStateId, UUID onPrClosedStateId) {
            this(onBranchStateId, onPrOpenedStateId, onPrMergedStateId, onPrClosedStateId, GithubText.CODE_TITLE);
        }
    }

    /**
     * @param lastRunAt     cuándo se ejecutó por última vez ({@code null} si nunca)
     * @param lastRunStatus cómo va esa ejecución ({@code requested}, {@code queued}, {@code in_progress}, {@code completed})
     */
    public record Rule(UUID id, UUID projectId, String firesOn, UUID repositoryId, String repository, long workflowId,
                       String workflowName, String ref, Instant lastRunAt, String lastRunStatus, String lastRunConclusion) {
    }

    /** Una tarea encontrada por su código, con lo que hace falta para decidir si se mueve. */
    public record IssueRef(UUID id, UUID projectId, String stateGroup) {
    }

    // ----- Instalación

    private static final String INSTALLATION = """
            SELECT g.id, g.workspace_id, g.installation_id, g.account_login, g.account_type, u.full_name, g.last_event_at, g.created_at
            FROM github_installations g LEFT JOIN users u ON u.id = g.installed_by_id
            """;
    private static final RowMapper<Installation> INSTALLATION_ROW = (row, index) -> new Installation(uuid(row, 1), uuid(row, 2),
            row.getLong(3), row.getString(4), row.getString(5), row.getString(6), instant(row, 7), instant(row, 8));

    public Optional<Installation> installationOf(UUID workspaceId) {
        return jdbc.query(INSTALLATION + " WHERE g.workspace_id = ?", INSTALLATION_ROW, workspaceId).stream().findFirst();
    }

    public Optional<Installation> installation(long installationId) {
        return jdbc.query(INSTALLATION + " WHERE g.installation_id = ?", INSTALLATION_ROW, installationId).stream().findFirst();
    }

    /** Guarda dónde quedó instalada la app para el workspace (si ya tenía otra instalación, la reemplaza). */
    public void saveInstallation(UUID workspaceId, long installationId, String accountLogin, String accountType, UUID installedById) {
        jdbc.update("DELETE FROM github_installations WHERE workspace_id = ? OR installation_id = ?", workspaceId, installationId);
        jdbc.update("""
                INSERT INTO github_installations (id, workspace_id, installation_id, account_login, account_type, installed_by_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), workspaceId, installationId, accountLogin, accountType, installedById);
    }

    /** Quita la instalación con todo lo suyo (repositorios, ramas, PR y ejecuciones caen con ella). */
    public void deleteInstallation(long installationId) {
        jdbc.update("DELETE FROM github_installations WHERE installation_id = ?", installationId);
    }

    public void touch(long installationId) {
        jdbc.update("UPDATE github_installations SET last_event_at = now(), updated_at = now() WHERE installation_id = ?", installationId);
    }

    /** @return {@code false} si ese aviso ya se había atendido */
    public boolean firstDelivery(String deliveryId) {
        return jdbc.update("INSERT INTO github_webhook_deliveries (delivery_id) VALUES (?) ON CONFLICT DO NOTHING", deliveryId) == 1;
    }

    public void forgetOldDeliveries() {
        jdbc.update("DELETE FROM github_webhook_deliveries WHERE received_at < now() - interval '7 days'");
    }

    // ----- Repositorios

    private static final String REPOSITORY = """
            SELECT r.id, r.installation_id, g.installation_id, g.workspace_id, r.github_id, r.full_name, r.default_branch, r.is_private
            FROM github_repositories r JOIN github_installations g ON g.id = r.installation_id
            """;
    private static final RowMapper<Repository> REPOSITORY_ROW = (row, index) -> new Repository(uuid(row, 1), uuid(row, 2), row.getLong(3),
            uuid(row, 4), row.getLong(5), row.getString(6), row.getString(7), row.getBoolean(8));

    public List<Repository> repositoriesOf(UUID workspaceId) {
        return jdbc.query(REPOSITORY + " WHERE g.workspace_id = ? ORDER BY lower(r.full_name)", REPOSITORY_ROW, workspaceId);
    }

    public List<Repository> repositoriesOfProject(UUID projectId) {
        return jdbc.query(REPOSITORY + " JOIN project_repositories pr ON pr.repository_id = r.id WHERE pr.project_id = ? ORDER BY lower(r.full_name)",
                REPOSITORY_ROW, projectId);
    }

    public Optional<Repository> repository(UUID id) {
        return jdbc.query(REPOSITORY + " WHERE r.id = ?", REPOSITORY_ROW, id).stream().findFirst();
    }

    /** El repositorio de una instalación por su identificador en GitHub. */
    public Optional<Repository> repository(long installationId, long githubId) {
        return jdbc.query(REPOSITORY + " WHERE g.installation_id = ? AND r.github_id = ?", REPOSITORY_ROW, installationId, githubId)
                .stream().findFirst();
    }

    public void saveRepository(UUID installationRowId, long githubId, String fullName, String defaultBranch, boolean isPrivate) {
        jdbc.update("""
                INSERT INTO github_repositories (id, installation_id, github_id, full_name, default_branch, is_private)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (installation_id, github_id) DO UPDATE
                SET full_name = EXCLUDED.full_name, default_branch = coalesce(EXCLUDED.default_branch, github_repositories.default_branch),
                    is_private = EXCLUDED.is_private, updated_at = now()
                """, UUID.randomUUID(), installationRowId, githubId, fullName, defaultBranch, isPrivate);
    }

    /** Quita los repositorios de la instalación que ya no están en la lista (dejaron de compartirse con la app). */
    public void keepOnlyRepositories(UUID installationRowId, Collection<Long> githubIds) {
        if (githubIds.isEmpty()) {
            jdbc.update("DELETE FROM github_repositories WHERE installation_id = ?", installationRowId);
            return;
        }
        String marks = String.join(",", githubIds.stream().map(id -> "?").toList());
        Object[] args = new Object[githubIds.size() + 1];
        args[0] = installationRowId;
        int at = 1;
        for (Long id : githubIds) {
            args[at++] = id;
        }
        jdbc.update("DELETE FROM github_repositories WHERE installation_id = ? AND github_id NOT IN (" + marks + ")", args);
    }

    public void deleteRepository(long installationId, long githubId) {
        jdbc.update("""
                DELETE FROM github_repositories r USING github_installations g
                WHERE g.id = r.installation_id AND g.installation_id = ? AND r.github_id = ?
                """, installationId, githubId);
    }

    public List<LinkedProject> linkedProjects(UUID workspaceId) {
        return jdbc.query("""
                SELECT pr.repository_id, p.id, p.identifier, p.name, p.color
                FROM project_repositories pr JOIN projects p ON p.id = pr.project_id
                WHERE p.workspace_id = ? ORDER BY lower(p.name)
                """, (row, index) -> new LinkedProject(uuid(row, 1), uuid(row, 2), row.getString(3), row.getString(4), row.getString(5)),
                workspaceId);
    }

    public void setProjectRepositories(UUID projectId, Collection<UUID> repositoryIds) {
        jdbc.update("DELETE FROM project_repositories WHERE project_id = ?", projectId);
        for (UUID repositoryId : repositoryIds) {
            jdbc.update("INSERT INTO project_repositories (project_id, repository_id) VALUES (?, ?)", projectId, repositoryId);
        }
    }

    public boolean projectHasRepository(UUID projectId, UUID repositoryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM project_repositories WHERE project_id = ? AND repository_id = ?)", Boolean.class,
                projectId, repositoryId));
    }

    // ----- Tareas por su código

    /** La tarea con ese código entre los proyectos que tienen enlazado el repositorio. */
    public Optional<IssueRef> issueByCode(UUID repositoryId, String identifier, int number) {
        return jdbc.query("""
                SELECT i.id, i.project_id, s.state_group
                FROM issues i JOIN projects p ON p.id = i.project_id JOIN states s ON s.id = i.state_id
                     JOIN project_repositories pr ON pr.project_id = p.id
                WHERE pr.repository_id = ? AND upper(p.identifier) = upper(?) AND i.sequence_id = ?
                """, (row, index) -> new IssueRef(uuid(row, 1), uuid(row, 2), row.getString(3)), repositoryId, identifier, number)
                .stream().findFirst();
    }

    public Optional<IssueRef> issue(UUID issueId) {
        return jdbc.query("SELECT i.id, i.project_id, s.state_group FROM issues i JOIN states s ON s.id = i.state_id WHERE i.id = ?",
                (row, index) -> new IssueRef(uuid(row, 1), uuid(row, 2), row.getString(3)), issueId).stream().findFirst();
    }

    // ----- Ramas

    private static final String BRANCH = """
            SELECT b.id, b.repository_id, r.full_name, b.issue_id, p.identifier || '-' || i.sequence_id, i.title, b.name, b.created_at
            FROM github_branches b JOIN github_repositories r ON r.id = b.repository_id
                 JOIN issues i ON i.id = b.issue_id JOIN projects p ON p.id = i.project_id
            """;
    private static final RowMapper<Branch> BRANCH_ROW = (row, index) -> new Branch(uuid(row, 1), uuid(row, 2), row.getString(3),
            uuid(row, 4), row.getString(5), row.getString(6), row.getString(7), instant(row, 8));

    /** @return {@code true} si la rama no estaba enlazada a esa tarea */
    public boolean linkBranch(UUID repositoryId, UUID issueId, String name) {
        return jdbc.update("""
                INSERT INTO github_branches (id, repository_id, issue_id, name) VALUES (?, ?, ?, ?)
                ON CONFLICT (repository_id, name, issue_id) DO NOTHING
                """, UUID.randomUUID(), repositoryId, issueId, name) == 1;
    }

    public void deleteBranch(UUID repositoryId, String name) {
        jdbc.update("DELETE FROM github_branches WHERE repository_id = ? AND name = ?", repositoryId, name);
    }

    /** Las ramas enlazadas a tareas del proyecto, las más recientes primero. */
    public List<Branch> branchesOfProject(UUID projectId, int limit) {
        return jdbc.query(BRANCH + " WHERE i.project_id = ? ORDER BY b.created_at DESC LIMIT ?", BRANCH_ROW,
                projectId, limit);
    }

    public List<Branch> branchesOf(UUID issueId) {
        return jdbc.query(BRANCH + " WHERE b.issue_id = ? ORDER BY b.created_at", BRANCH_ROW, issueId);
    }

    // ----- Pull requests

    private static final String PULL = """
            SELECT pr.id, pr.repository_id, r.full_name, pr.issue_id,
                   CASE WHEN i.id IS NULL THEN NULL ELSE p.identifier || '-' || i.sequence_id END, i.title, s.name, s.state_group, i.project_id,
                   pr.number, pr.title, pr.state, pr.url, pr.author_login, pr.head_branch, pr.base_branch, pr.head_sha, pr.reviewers,
                   pr.checks_state, pr.checks_total, pr.checks_failed, pr.merged_at, pr.opened_at, pr.changed_at, au.id, au.full_name
            FROM github_pull_requests pr JOIN github_repositories r ON r.id = pr.repository_id
                 JOIN github_installations gi ON gi.id = r.installation_id
                 LEFT JOIN issues i ON i.id = pr.issue_id LEFT JOIN projects p ON p.id = i.project_id LEFT JOIN states s ON s.id = i.state_id
                 -- Quien lo abrió, si es alguien del workspace que enlazó su cuenta de GitHub
                 LEFT JOIN github_accounts ga ON lower(ga.login) = lower(pr.author_login)
                      AND EXISTS (SELECT 1 FROM workspace_members wm WHERE wm.workspace_id = gi.workspace_id AND wm.user_id = ga.user_id)
                 LEFT JOIN users au ON au.id = ga.user_id
            """;
    private static final RowMapper<PullRequest> PULL_ROW = (row, index) -> new PullRequest(uuid(row, 1), uuid(row, 2), row.getString(3),
            uuid(row, 4), row.getString(5), row.getString(6), row.getString(7), row.getString(8), uuid(row, 9), row.getInt(10),
            row.getString(11), row.getString(12), row.getString(13), row.getString(14), row.getString(15), row.getString(16),
            row.getString(17), row.getString(18), row.getString(19), row.getInt(20), row.getInt(21), instant(row, 22), instant(row, 23),
            instant(row, 24), uuid(row, 25), row.getString(26));

    public Optional<PullRequest> pullRequest(UUID repositoryId, int number) {
        return jdbc.query(PULL + " WHERE pr.repository_id = ? AND pr.number = ?", PULL_ROW, repositoryId, number).stream().findFirst();
    }

    /** Los pull requests que están en ese commit. */
    public List<PullRequest> pullRequestsAt(UUID repositoryId, String headSha) {
        return jdbc.query(PULL + " WHERE pr.repository_id = ? AND pr.head_sha = ?", PULL_ROW, repositoryId, headSha);
    }

    /** Cuántos pull requests abiertos (o en borrador) tiene cada repositorio del proyecto. */
    public java.util.Map<UUID, Integer> openPullRequests(UUID projectId) {
        java.util.Map<UUID, Integer> counts = new java.util.HashMap<>();
        jdbc.query("""
                SELECT pr.repository_id, count(*) FROM github_pull_requests pr
                     JOIN project_repositories link ON link.repository_id = pr.repository_id
                WHERE link.project_id = ? AND pr.state IN ('OPEN', 'DRAFT') GROUP BY pr.repository_id
                """, row -> {
            counts.put(uuid(row, 1), row.getInt(2));
        }, projectId);
        return counts;
    }

    public Optional<PullRequest> pullRequest(UUID id) {
        return jdbc.query(PULL + " WHERE pr.id = ?", PULL_ROW, id).stream().findFirst();
    }

    /** Guarda el PR como está ahora en GitHub. La tarea enlazada solo se pone si aún no tenía una. */
    public void savePullRequest(UUID repositoryId, int number, String title, String state, String url, String authorLogin,
                                String headBranch, String baseBranch, String headSha, String reviewers, Instant mergedAt,
                                Instant openedAt, Instant changedAt, UUID issueId) {
        jdbc.update("""
                INSERT INTO github_pull_requests (id, repository_id, number, title, state, url, author_login, head_branch, base_branch,
                                                  head_sha, reviewers, merged_at, opened_at, changed_at, issue_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (repository_id, number) DO UPDATE
                SET title = EXCLUDED.title, state = EXCLUDED.state, url = EXCLUDED.url, author_login = EXCLUDED.author_login,
                    head_branch = EXCLUDED.head_branch, base_branch = EXCLUDED.base_branch,
                    checks_state = CASE WHEN github_pull_requests.head_sha IS DISTINCT FROM EXCLUDED.head_sha THEN NULL ELSE github_pull_requests.checks_state END,
                    head_sha = EXCLUDED.head_sha, reviewers = EXCLUDED.reviewers, merged_at = EXCLUDED.merged_at,
                    opened_at = coalesce(github_pull_requests.opened_at, EXCLUDED.opened_at), changed_at = EXCLUDED.changed_at,
                    issue_id = coalesce(github_pull_requests.issue_id, EXCLUDED.issue_id), updated_at = now()
                """, UUID.randomUUID(), repositoryId, number, cut(title, 500), state, cut(url, 500), authorLogin, cut(headBranch, 255),
                cut(baseBranch, 255), headSha, cut(reviewers, 500), timestamp(mergedAt), timestamp(openedAt),
                timestamp(changedAt == null ? Instant.now() : changedAt), issueId);
    }

    public void setPullRequestIssue(UUID pullRequestId, UUID issueId) {
        jdbc.update("UPDATE github_pull_requests SET issue_id = ?, updated_at = now() WHERE id = ?", issueId, pullRequestId);
    }

    /** El resultado de los checks del commit en el que está un PR (los de otro commit ya no cuentan). */
    public void setChecks(UUID repositoryId, String headSha, String state, int total, int failed) {
        jdbc.update("""
                UPDATE github_pull_requests SET checks_state = ?, checks_total = ?, checks_failed = ?, updated_at = now()
                WHERE repository_id = ? AND head_sha = ?
                """, state, total, failed, repositoryId, headSha);
    }

    /** Los PR de los repositorios del proyecto: {@code states} filtra por estado (vacío = todos). */
    public List<PullRequest> pullRequestsOfProject(UUID projectId, Collection<String> states, int limit) {
        String filter = states.isEmpty() ? "" : " AND pr.state IN (" + String.join(",", states.stream().map(state -> "'" + state + "'").toList()) + ")";
        return jdbc.query(PULL + " JOIN project_repositories link ON link.repository_id = pr.repository_id WHERE link.project_id = ?" + filter
                + " ORDER BY pr.changed_at DESC LIMIT ?", PULL_ROW, projectId, limit);
    }

    public List<PullRequest> pullRequestsOf(UUID issueId) {
        return jdbc.query(PULL + " WHERE pr.issue_id = ? ORDER BY pr.opened_at NULLS LAST, pr.number", PULL_ROW, issueId);
    }

    // ----- Ejecuciones de Actions

    private static final String RUN = """
            SELECT w.id, w.repository_id, r.full_name, w.run_id, w.workflow_id, w.workflow_name, w.title, w.branch, w.head_sha, w.event,
                   w.status, w.conclusion, w.actor_login, w.url, w.pr_number, w.issue_id,
                   CASE WHEN i.id IS NULL THEN NULL ELSE p.identifier || '-' || i.sequence_id END, w.rule_id, u.full_name,
                   w.requested_at, w.started_at, w.changed_at
            FROM github_workflow_runs w JOIN github_repositories r ON r.id = w.repository_id
                 LEFT JOIN issues i ON i.id = w.issue_id LEFT JOIN projects p ON p.id = i.project_id LEFT JOIN users u ON u.id = w.triggered_by_id
            """;
    private static final RowMapper<Run> RUN_ROW = (row, index) -> new Run(uuid(row, 1), uuid(row, 2), row.getString(3), longOrNull(row, 4),
            longOrNull(row, 5), row.getString(6), row.getString(7), row.getString(8), row.getString(9), row.getString(10),
            row.getString(11), row.getString(12), row.getString(13), row.getString(14), intOrNull(row, 15), uuid(row, 16),
            row.getString(17), uuid(row, 18), row.getString(19), instant(row, 20), instant(row, 21), instant(row, 22));

    /**
     * Una ejecución que TaskFlow acaba de pedir: todavía no tiene número en GitHub; se completa cuando GitHub avisa de
     * ella ({@link #claimRequested}).
     */
    public UUID requestRun(UUID repositoryId, long workflowId, String workflowName, String ref, UUID issueId, UUID ruleId, UUID triggeredById) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO github_workflow_runs (id, repository_id, workflow_id, workflow_name, branch, event, status, issue_id, rule_id,
                                                  triggered_by_id, requested_at)
                VALUES (?, ?, ?, ?, ?, 'workflow_dispatch', 'requested', ?, ?, ?, now())
                """, id, repositoryId, workflowId, cut(workflowName, 200), cut(ref, 255), issueId, ruleId, triggeredById);
        return id;
    }

    /**
     * La ejecución pedida desde TaskFlow que corresponde a la que GitHub acaba de anunciar: la más antigua sin número,
     * del mismo workflow y rama, pedida en los últimos diez minutos. Se queda con ese número.
     *
     * @return si había una esperando
     */
    public boolean claimRequested(UUID repositoryId, long workflowId, String branch, long runId) {
        return jdbc.update("""
                UPDATE github_workflow_runs SET run_id = ?
                WHERE id = (SELECT id FROM github_workflow_runs
                            WHERE repository_id = ? AND run_id IS NULL AND workflow_id = ? AND branch = ?
                              AND requested_at > now() - interval '10 minutes'
                            ORDER BY requested_at LIMIT 1)
                  AND NOT EXISTS (SELECT 1 FROM github_workflow_runs WHERE repository_id = ? AND run_id = ?)
                """, runId, repositoryId, workflowId, branch, repositoryId, runId) == 1;
    }

    /** Guarda la ejecución como está ahora en GitHub. La tarea solo se pone si aún no tenía una. */
    public void saveRun(UUID repositoryId, long runId, Long workflowId, String workflowName, String title, String branch, String headSha,
                        String event, String status, String conclusion, String actorLogin, String url, Integer prNumber, UUID issueId,
                        Instant startedAt, Instant changedAt) {
        jdbc.update("""
                INSERT INTO github_workflow_runs (id, repository_id, run_id, workflow_id, workflow_name, title, branch, head_sha, event,
                                                  status, conclusion, actor_login, url, pr_number, issue_id, started_at, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (repository_id, run_id) DO UPDATE
                SET workflow_id = EXCLUDED.workflow_id, workflow_name = EXCLUDED.workflow_name, title = EXCLUDED.title,
                    branch = EXCLUDED.branch, head_sha = EXCLUDED.head_sha, event = EXCLUDED.event, status = EXCLUDED.status,
                    conclusion = EXCLUDED.conclusion, actor_login = EXCLUDED.actor_login, url = EXCLUDED.url,
                    pr_number = coalesce(EXCLUDED.pr_number, github_workflow_runs.pr_number),
                    issue_id = coalesce(github_workflow_runs.issue_id, EXCLUDED.issue_id),
                    started_at = EXCLUDED.started_at, changed_at = EXCLUDED.changed_at, updated_at = now()
                """, UUID.randomUUID(), repositoryId, runId, workflowId, cut(workflowName, 200), cut(title, 500), cut(branch, 255), headSha,
                event, status, conclusion, actorLogin, cut(url, 500), prNumber, issueId, timestamp(startedAt),
                timestamp(changedAt == null ? Instant.now() : changedAt));
    }

    public void failRequestedRun(UUID id) {
        jdbc.update("DELETE FROM github_workflow_runs WHERE id = ? AND run_id IS NULL", id);
    }

    public Optional<Run> run(UUID id) {
        return jdbc.query(RUN + " WHERE w.id = ?", RUN_ROW, id).stream().findFirst();
    }

    /**
     * Una ejecución que TaskFlow pidió y GitHub no anunció en una hora ya no va a llegar (el aviso se perdió o el
     * workflow no arrancó): deja de mostrarse como pendiente.
     */
    private static final String NOT_LOST = " AND NOT (w.run_id IS NULL AND w.requested_at < now() - interval '1 hour')";

    public List<Run> runsOfProject(UUID projectId, int limit) {
        return jdbc.query(RUN + " JOIN project_repositories link ON link.repository_id = w.repository_id WHERE link.project_id = ?" + NOT_LOST
                + " ORDER BY coalesce(w.started_at, w.requested_at, w.changed_at) DESC LIMIT ?", RUN_ROW, projectId, limit);
    }

    public List<Run> runsOf(UUID issueId, int limit) {
        return jdbc.query(RUN + " WHERE w.issue_id = ?" + NOT_LOST + " ORDER BY coalesce(w.started_at, w.requested_at, w.changed_at) DESC LIMIT ?",
                RUN_ROW, issueId, limit);
    }

    // ----- Ajustes y reglas del proyecto

    public Settings settings(UUID projectId) {
        return jdbc.query("""
                SELECT on_branch_state_id, on_pr_opened_state_id, on_pr_merged_state_id, on_pr_closed_state_id, branch_format
                FROM github_project_settings WHERE project_id = ?
                """, (row, index) -> new Settings(uuid(row, 1), uuid(row, 2), uuid(row, 3), uuid(row, 4), row.getString(5)), projectId)
                .stream().findFirst().orElse(Settings.NONE);
    }

    public void saveSettings(UUID projectId, Settings settings) {
        jdbc.update("""
                INSERT INTO github_project_settings (project_id, on_branch_state_id, on_pr_opened_state_id, on_pr_merged_state_id,
                                                     on_pr_closed_state_id, branch_format)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE
                SET on_branch_state_id = EXCLUDED.on_branch_state_id, on_pr_opened_state_id = EXCLUDED.on_pr_opened_state_id,
                    on_pr_merged_state_id = EXCLUDED.on_pr_merged_state_id, on_pr_closed_state_id = EXCLUDED.on_pr_closed_state_id,
                    branch_format = EXCLUDED.branch_format, updated_at = now()
                """, projectId, settings.onBranchStateId(), settings.onPrOpenedStateId(), settings.onPrMergedStateId(),
                settings.onPrClosedStateId(), settings.branchFormat());
    }

    private static final String RULE = """
            SELECT g.id, g.project_id, g.fires_on, g.repository_id, r.full_name, g.workflow_id, g.workflow_name, g.ref,
                   coalesce(last.started_at, last.requested_at, last.changed_at), last.status, last.conclusion
            FROM github_rules g JOIN github_repositories r ON r.id = g.repository_id
                 LEFT JOIN LATERAL (
                     SELECT w.started_at, w.requested_at, w.changed_at, w.status, w.conclusion FROM github_workflow_runs w
                     WHERE w.rule_id = g.id AND NOT (w.run_id IS NULL AND w.requested_at < now() - interval '1 hour') ORDER BY coalesce(w.started_at, w.requested_at, w.changed_at) DESC LIMIT 1) last ON TRUE
            """;
    private static final RowMapper<Rule> RULE_ROW = (row, index) -> new Rule(uuid(row, 1), uuid(row, 2), row.getString(3), uuid(row, 4),
            row.getString(5), row.getLong(6), row.getString(7), row.getString(8), instant(row, 9), row.getString(10), row.getString(11));

    public List<Rule> rules(UUID projectId) {
        return jdbc.query(RULE + " WHERE g.project_id = ? ORDER BY g.created_at", RULE_ROW, projectId);
    }

    public List<Rule> rules(UUID projectId, String firesOn) {
        return jdbc.query(RULE + " WHERE g.project_id = ? AND g.fires_on = ? ORDER BY g.created_at", RULE_ROW, projectId, firesOn);
    }

    public UUID addRule(UUID projectId, String firesOn, UUID repositoryId, long workflowId, String workflowName, String ref, UUID createdById) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO github_rules (id, project_id, fires_on, repository_id, workflow_id, workflow_name, ref, created_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, firesOn, repositoryId, workflowId, cut(workflowName, 200), cut(ref, 255), createdById);
        return id;
    }

    public boolean deleteRule(UUID projectId, UUID ruleId) {
        return jdbc.update("DELETE FROM github_rules WHERE id = ? AND project_id = ?", ruleId, projectId) == 1;
    }

    // ----- Resumen por tarea

    /**
     * Lo que se ve de GitHub en la tarjeta o la fila de una tarea: su rama más reciente, cuántas tiene y su pull
     * request más relevante (uno abierto antes que un borrador, este antes que uno fusionado, y al final uno cerrado).
     */
    public record IssueSummary(UUID issueId, String branch, int branches, Integer prNumber, String prState, String checksState,
                               String prUrl, boolean running) {
    }

    /** Las tareas del proyecto que tienen rama o pull request. */
    public List<IssueSummary> summaryOfProject(UUID projectId) {
        return jdbc.query("""
                SELECT i.id,
                       (SELECT b.name FROM github_branches b WHERE b.issue_id = i.id ORDER BY b.created_at DESC LIMIT 1),
                       (SELECT count(*) FROM github_branches b WHERE b.issue_id = i.id),
                       pr.number, pr.state, pr.checks_state, pr.url,
                       EXISTS (SELECT 1 FROM github_workflow_runs w WHERE w.issue_id = i.id AND w.status <> 'completed'
                               AND coalesce(w.started_at, w.requested_at, w.changed_at)
                                   > now() - CASE WHEN w.run_id IS NULL THEN interval '10 minutes' ELSE interval '2 hours' END)
                FROM issues i
                     LEFT JOIN LATERAL (
                         SELECT p.number, p.state, p.checks_state, p.url FROM github_pull_requests p WHERE p.issue_id = i.id
                         ORDER BY CASE p.state WHEN 'OPEN' THEN 0 WHEN 'DRAFT' THEN 1 WHEN 'MERGED' THEN 2 ELSE 3 END,
                                  p.changed_at DESC NULLS LAST
                         LIMIT 1) pr ON TRUE
                WHERE i.project_id = ?
                  AND (pr.number IS NOT NULL OR EXISTS (SELECT 1 FROM github_branches b WHERE b.issue_id = i.id))
                """, (row, index) -> new IssueSummary(uuid(row, 1), row.getString(2), row.getInt(3), intOrNull(row, 4), row.getString(5),
                row.getString(6), row.getString(7), row.getBoolean(8)), projectId);
    }

    // ----- La cuenta de GitHub de cada persona

    /** Los tokens vienen cifrados, como se guardan. */
    public record Account(UUID userId, long githubId, String login, String avatarUrl, String accessToken, String refreshToken,
                          Instant accessExpiresAt, Instant refreshExpiresAt, Instant createdAt) {
    }

    /** Alguien del workspace y su usuario de GitHub ({@code null} si no ha enlazado su cuenta). */
    public record MemberAccount(UUID userId, String fullName, String login) {
    }

    public Optional<Account> account(UUID userId) {
        return jdbc.query("""
                SELECT user_id, github_id, login, avatar_url, access_token, refresh_token, access_expires_at, refresh_expires_at, created_at
                FROM github_accounts WHERE user_id = ?
                """, (row, index) -> new Account(uuid(row, 1), row.getLong(2), row.getString(3), row.getString(4), row.getString(5),
                row.getString(6), instant(row, 7), instant(row, 8), instant(row, 9)), userId).stream().findFirst();
    }

    /** De quién es esa cuenta de GitHub en TaskFlow. */
    public Optional<UUID> accountOwner(long githubId) {
        return jdbc.query("SELECT user_id FROM github_accounts WHERE github_id = ?", (row, index) -> uuid(row, 1), githubId).stream().findFirst();
    }

    public void saveAccount(UUID userId, long githubId, String login, String avatarUrl, String accessToken, String refreshToken,
                            Instant accessExpiresAt, Instant refreshExpiresAt) {
        jdbc.update("""
                INSERT INTO github_accounts (user_id, github_id, login, avatar_url, access_token, refresh_token, access_expires_at,
                                             refresh_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET github_id = EXCLUDED.github_id, login = EXCLUDED.login, avatar_url = EXCLUDED.avatar_url,
                    access_token = EXCLUDED.access_token, refresh_token = EXCLUDED.refresh_token,
                    access_expires_at = EXCLUDED.access_expires_at, refresh_expires_at = EXCLUDED.refresh_expires_at,
                    created_at = now(), updated_at = now()
                """, userId, githubId, cut(login, 100), cut(avatarUrl, 500), accessToken, refreshToken, timestamp(accessExpiresAt),
                timestamp(refreshExpiresAt));
    }

    public void saveAccountTokens(UUID userId, String accessToken, String refreshToken, Instant accessExpiresAt, Instant refreshExpiresAt) {
        jdbc.update("""
                UPDATE github_accounts SET access_token = ?, refresh_token = ?, access_expires_at = ?, refresh_expires_at = ?, updated_at = now()
                WHERE user_id = ?
                """, accessToken, refreshToken, timestamp(accessExpiresAt), timestamp(refreshExpiresAt), userId);
    }

    public void deleteAccount(UUID userId) {
        jdbc.update("DELETE FROM github_accounts WHERE user_id = ?", userId);
    }

    /** Los miembros del workspace con su usuario de GitHub, los enlazados primero. */
    public List<MemberAccount> memberAccounts(UUID workspaceId) {
        return jdbc.query("""
                SELECT u.id, u.full_name, ga.login
                FROM workspace_members wm JOIN users u ON u.id = wm.user_id LEFT JOIN github_accounts ga ON ga.user_id = u.id
                WHERE wm.workspace_id = ? ORDER BY (ga.login IS NULL), lower(u.full_name)
                """, (row, index) -> new MemberAccount(uuid(row, 1), row.getString(2), row.getString(3)), workspaceId);
    }

    /** La persona del workspace de ese repositorio que tiene enlazado ese usuario de GitHub. */
    public Optional<UUID> memberByLogin(UUID repositoryId, String login) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT ga.user_id
                FROM github_accounts ga JOIN workspace_members wm ON wm.user_id = ga.user_id
                     JOIN github_installations gi ON gi.workspace_id = wm.workspace_id
                     JOIN github_repositories r ON r.installation_id = gi.id
                WHERE r.id = ? AND lower(ga.login) = lower(?)
                """, (row, index) -> uuid(row, 1), repositoryId, login).stream().findFirst();
    }

    // ----- Utilidades

    private static UUID uuid(ResultSet row, int column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long longOrNull(ResultSet row, int column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Integer intOrNull(ResultSet row, int column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
