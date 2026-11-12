package com.taskflow.api.integration;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.ReportOptions;
import com.taskflow.api.dto.response.DevelopmentStatsResponse;
import com.taskflow.api.dto.response.DevelopmentStatsResponse.MergedWeek;
import com.taskflow.api.dto.response.DevelopmentStatsResponse.OldPullRequest;
import com.taskflow.api.dto.response.DevelopmentStatsResponse.WorkflowStats;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.IssuesReportPreview;
import com.taskflow.api.entity.User;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.DevelopmentStatsService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.ReportService;
import com.taskflow.api.service.ReportService.ReportFile;
import com.taskflow.api.service.WorkspaceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Las estadísticas de desarrollo (GitHub) contra un PostgreSQL de verdad ({@link TestDatabase}): pull requests y
 * ejecuciones de Actions de los repositorios del proyecto (y solo de esos), y la sección del reporte. Los datos de
 * GitHub se insertan directo en sus tablas, como los dejarían los avisos.
 */
@SpringBootTest
class DevelopmentStatsIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final IssueFilter NO_FILTER = new IssueFilter(null, null, null, null, null, null, null, null, null);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
        registry.add("app.jwt.secret", () -> "secreto-de-integracion-con-mas-de-32-caracteres");
        registry.add("app.mail.provider", () -> "resend");
        registry.add("app.mail.resend-api-key", () -> "");
        registry.add("app.frontend-url", () -> "http://localhost:5173");
        registry.add("app.maintenance.cron", () -> "-");
        // Este contexto es propio de esta prueba (otras propiedades): pocas conexiones, para no agotar las de PostgreSQL
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @BeforeAll
    static void requirePostgres() {
        assumeTrue(TestDatabase.available(), "PostgreSQL no disponible (DB_URL)");
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private IssueService issueService;
    @Autowired
    private DevelopmentStatsService developmentStats;
    @Autowired
    private ReportService reportService;
    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;
    private User pm;
    private String slug;
    private UUID web;
    private UUID ops;
    private UUID repo;
    private UUID otherRepo;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = new User();
        pm.setEmail("pm-" + suffix + "@test.com");
        pm.setFullName("Paula PM");
        pm.setPasswordHash(passwordEncoder.encode("Clave1234"));
        pm.setEmailVerified(true);
        pm = userRepository.saveAndFlush(pm);
        slug = workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Equipo " + suffix, "eq-" + suffix)).slug();
        web = projectService.create(slug, pm.getId(), new CreateProjectRequest("Web", code("W"), null, null)).id();
        ops = projectService.create(slug, pm.getId(), new CreateProjectRequest("Ops", code("O"), null, null)).id();

        UUID workspaceId = jdbc.queryForObject("SELECT id FROM workspaces WHERE slug = ?", UUID.class, slug);
        UUID installation = UUID.randomUUID();
        jdbc.update("INSERT INTO github_installations (id, workspace_id, installation_id, account_login, account_type) "
                + "VALUES (?, ?, ?, 'acme', 'Organization')", installation, workspaceId, IDS.incrementAndGet());
        repo = repository(installation, "acme/web-" + suffix);
        // Otro repositorio del workspace que no es de Web: lo suyo no cuenta
        otherRepo = repository(installation, "acme/otro-" + suffix);
        jdbc.update("INSERT INTO project_repositories (project_id, repository_id) VALUES (?, ?)", web, repo);
    }

    private String code(String letter) {
        return letter + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X");
    }

    private UUID repository(UUID installation, String fullName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO github_repositories (id, installation_id, github_id, full_name) VALUES (?, ?, ?, ?)",
                id, installation, IDS.incrementAndGet(), fullName);
        return id;
    }

    private IssueResponse issue(String title) {
        return issueService.create(slug, web, pm.getId(), new CreateIssueRequest(title, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
    }

    /** La deja completada ahora (en el estado de Hecho del proyecto). */
    private void complete(UUID issueId) {
        jdbc.update("""
                UPDATE issues SET state_id = (SELECT id FROM states WHERE project_id = ? AND state_group = 'COMPLETED' LIMIT 1),
                                  completed_at = now()
                WHERE id = ?
                """, web, issueId);
    }

    private static Timestamp ago(Duration duration) {
        return Timestamp.from(Instant.now().minus(duration));
    }

    private void pull(UUID repositoryId, int number, String state, Duration openedAgo, Duration mergedAgo, UUID issueId) {
        jdbc.update("""
                INSERT INTO github_pull_requests (id, repository_id, number, title, state, url, opened_at, merged_at, issue_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), repositoryId, number, "PR " + number, state, "https://github.com/acme/web/pull/" + number,
                ago(openedAgo), mergedAgo == null ? null : ago(mergedAgo), issueId);
    }

    private void run(UUID repositoryId, long workflowId, String name, String status, String conclusion, Duration startedAgo,
                     Duration took) {
        Timestamp started = ago(startedAgo);
        jdbc.update("""
                INSERT INTO github_workflow_runs (id, repository_id, run_id, workflow_id, workflow_name, branch, event, status,
                                                  conclusion, started_at, changed_at)
                VALUES (?, ?, ?, ?, ?, 'main', 'push', ?, ?, ?, ?)
                """, UUID.randomUUID(), repositoryId, IDS.incrementAndGet(), workflowId, name, status, conclusion, started,
                Timestamp.from(started.toInstant().plus(took)));
    }

    @Test
    void cuentaLosPullRequestsYLasEjecucionesDeLosRepositoriosDelProyecto() {
        IssueResponse login = issue("Login con Google");
        IssueResponse filtros = issue("Filtros de la tabla");
        IssueResponse textos = issue("Revisar textos");
        complete(login.id());
        complete(textos.id());

        // Fusionados en el periodo: 10 y 20 horas de apertura a fusión (promedio 15)
        pull(repo, 1, "MERGED", Duration.ofHours(34), Duration.ofHours(24), login.id());
        pull(repo, 2, "MERGED", Duration.ofHours(30), Duration.ofHours(10), null);
        // Fusionado hace 20 semanas: fuera del periodo
        pull(repo, 3, "MERGED", Duration.ofDays(141), Duration.ofDays(140), null);
        // Abiertos: uno hace 10 días (viejo, con su tarea) y un borrador de ayer
        pull(repo, 4, "OPEN", Duration.ofDays(10), null, filtros.id());
        pull(repo, 5, "DRAFT", Duration.ofDays(1), null, null);
        pull(repo, 6, "CLOSED", Duration.ofDays(3), null, null);
        // Del otro repositorio: no cuenta
        pull(otherRepo, 1, "OPEN", Duration.ofDays(30), null, null);
        pull(otherRepo, 2, "MERGED", Duration.ofDays(2), Duration.ofDays(1), null);

        // CI: 3 correctas y 1 fallida (duraciones 60, 120, 180 y 60 s); la cancelada, la que sigue en curso y la de
        // hace 20 semanas no cuentan
        run(repo, 11, "CI", "completed", "success", Duration.ofHours(5), Duration.ofSeconds(60));
        run(repo, 11, "CI", "completed", "success", Duration.ofHours(4), Duration.ofSeconds(120));
        run(repo, 11, "CI", "completed", "failure", Duration.ofHours(3), Duration.ofSeconds(60));
        run(repo, 11, "CI", "completed", "success", Duration.ofHours(2), Duration.ofSeconds(180));
        run(repo, 11, "CI", "completed", "cancelled", Duration.ofHours(1), Duration.ofSeconds(5));
        run(repo, 11, "CI", "in_progress", null, Duration.ofMinutes(5), Duration.ZERO);
        run(repo, 11, "CI", "completed", "failure", Duration.ofDays(140), Duration.ofSeconds(60));
        run(repo, 12, "Deploy", "completed", "success", Duration.ofDays(2), Duration.ofSeconds(45));
        run(otherRepo, 13, "CI", "completed", "failure", Duration.ofHours(2), Duration.ofSeconds(60));

        DevelopmentStatsResponse stats = developmentStats.getStats(slug, web, 8);

        assertThat(stats.repositories()).isEqualTo(1);
        assertThat(stats.weeks()).isEqualTo(8);
        assertThat(stats.openPullRequests()).isEqualTo(2);
        assertThat(stats.draftPullRequests()).isEqualTo(1);
        assertThat(stats.mergedPullRequests()).isEqualTo(2);
        assertThat(stats.averageHoursToMerge()).isEqualTo(15.0);
        assertThat(stats.mergedByWeek()).hasSize(8);
        assertThat(stats.mergedByWeek().stream().mapToLong(MergedWeek::merged).sum()).isEqualTo(2);
        assertThat(stats.mergedByWeek().getFirst().weekStart()).isEqualTo(stats.periodStart());
        assertThat(stats.runsFinished()).isEqualTo(5);
        assertThat(stats.runsSucceeded()).isEqualTo(4);
        assertThat(stats.completedIssues()).isEqualTo(2);
        assertThat(stats.completedWithMergedPullRequest()).isEqualTo(1);

        assertThat(stats.workflows()).extracting(WorkflowStats::name).containsExactly("CI", "Deploy");
        WorkflowStats ci = stats.workflows().getFirst();
        assertThat(ci.repository()).isEqualTo("acme/web-" + suffix);
        assertThat(ci.runs()).isEqualTo(4);
        assertThat(ci.succeeded()).isEqualTo(3);
        assertThat(ci.averageSeconds()).isEqualTo(105L);
        assertThat(ci.lastConclusion()).isEqualTo("success");
        assertThat(ci.lastRunAt()).isBetween(now.minus(Duration.ofMinutes(125)), now.minus(Duration.ofMinutes(115)));

        assertThat(stats.oldOpenPullRequests()).singleElement().satisfies(old -> {
            assertThat(old.number()).isEqualTo(4);
            assertThat(old.draft()).isFalse();
            assertThat(old.daysOpen()).isBetween(9L, 11L);
            assertThat(old.issueId()).isEqualTo(filtros.id());
            assertThat(old.issueCode()).isEqualTo(filtros.code());
        });
        assertThat(stats.oldOpenPullRequests()).extracting(OldPullRequest::repository).containsOnly("acme/web-" + suffix);
    }

    @Test
    void unProyectoSinRepositoriosNoTraeNadaYElPeriodoSeValida() {
        pull(otherRepo, 1, "OPEN", Duration.ofDays(30), null, null);

        DevelopmentStatsResponse stats = developmentStats.getStats(slug, ops, 4);

        assertThat(stats.repositories()).isZero();
        assertThat(stats.openPullRequests()).isZero();
        assertThat(stats.workflows()).isEmpty();
        assertThat(stats.oldOpenPullRequests()).isEmpty();
        assertThatThrownBy(() -> developmentStats.getStats(slug, web, 5)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void elReporteLlevaLaSeccionDeDesarrolloSoloSiHayRepositorios() {
        issue("Algo");
        pull(repo, 1, "MERGED", Duration.ofHours(30), Duration.ofHours(10), null);
        run(repo, 11, "CI", "completed", "success", Duration.ofHours(2), Duration.ofSeconds(60));
        ReportOptions withDevelopment = new ReportOptions(null, null, null, null, null, null, null, null, true, 4);
        ReportOptions without = new ReportOptions(null, null, null, null, null, null, null, null, null, null);

        IssuesReportPreview preview = reportService.previewIssues(slug, web, pm.getId(), NO_FILTER, withDevelopment);
        assertThat(preview.githubRepositories()).isEqualTo(1);
        assertThat(preview.development()).isNotNull();
        assertThat(preview.development().weeks()).isEqualTo(4);
        assertThat(preview.development().mergedPullRequests()).isEqualTo(1);
        assertThat(preview.development().workflows()).extracting(WorkflowStats::name).containsExactly("CI");

        // La vista previa la trae aunque la sección esté apagada (encenderla no vuelve a pedir la vista previa)
        IssuesReportPreview plain = reportService.previewIssues(slug, web, pm.getId(), NO_FILTER, without);
        assertThat(plain.githubRepositories()).isEqualTo(1);
        assertThat(plain.development()).isNotNull();
        assertThat(plain.development().weeks()).isEqualTo(8);

        IssuesReportPreview noRepos = reportService.previewIssues(slug, ops, pm.getId(), NO_FILTER, withDevelopment);
        assertThat(noRepos.githubRepositories()).isZero();
        assertThat(noRepos.development()).isNull();

        // Solo la sección de desarrollo también vale
        ReportFile pdf = reportService.issuesPdf(slug, web, pm.getId(), NO_FILTER,
                new ReportOptions(null, null, null, null, null, null, false, false, true, 8));
        assertThat(pdf.content()).isNotEmpty();
        assertThatThrownBy(() -> reportService.issuesPdf(slug, web, pm.getId(), NO_FILTER,
                new ReportOptions(null, null, null, null, null, null, true, true, true, 6)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("developmentWeeks");
    }
}
