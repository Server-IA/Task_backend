package com.taskflow.api.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.api.controller.GithubWebhookController;
import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.github.GithubService;
import com.taskflow.api.github.GithubService.IssueDev;
import com.taskflow.api.github.GithubService.ProjectStatus;
import com.taskflow.api.github.GithubService.WorkspaceStatus;
import com.taskflow.api.github.GithubStore.PullRequest;
import com.taskflow.api.github.GithubStore.Run;
import com.taskflow.api.github.GithubStore.Settings;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.StateService;
import com.taskflow.api.service.WorkspaceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * La integración con GitHub de punta a punta, contra PostgreSQL y un GitHub de mentira: conectar el workspace, los
 * avisos de ramas y pull requests que mueven la tarea, las reglas que ejecutan workflows y la rama creada desde la tarea.
 */
@SpringBootTest
class GithubIntegrationTest {

    private static final String SECRET = "secreto-de-los-avisos";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeGithub GITHUB = new FakeGithub();
    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
        registry.add("app.jwt.secret", () -> "secreto-de-integracion-con-mas-de-32-caracteres");
        registry.add("app.mail.provider", () -> "resend");
        registry.add("app.mail.resend-api-key", () -> "");
        registry.add("app.frontend-url", () -> "http://localhost:5173");
        registry.add("app.maintenance.cron", () -> "-");
        // Este contexto es propio de esta prueba (otras propiedades): pocas conexiones, para no agotar las de PostgreSQL
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.github.app-id", () -> "4242");
        registry.add("app.github.app-slug", () -> "taskflow-pruebas");
        registry.add("app.github.private-key-path", () -> GITHUB.keyFile().toString());
        registry.add("app.github.webhook-secret", () -> SECRET);
        registry.add("app.github.api-url", GITHUB::url);
        registry.add("app.github.web-url", () -> "https://github.test");
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
    private StateService stateService;
    @Autowired
    private IssueService issueService;
    @Autowired
    private GithubService githubService;
    @Autowired
    private GithubWebhookController webhookController;

    private String suffix;
    private User pm;
    private String slug;
    private UUID projectId;
    private String identifier;
    private UUID todo;
    private UUID started;
    private UUID done;
    private long installationId;
    private long repositoryGithubId;
    private String repositoryName;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = user("pm-" + suffix + "@test.com", "Paula PM");
        slug = workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Equipo " + suffix, "gh-" + suffix)).slug();
        identifier = "G" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X");
        projectId = projectService.create(slug, pm.getId(), new CreateProjectRequest("Web", identifier, null, null)).id();
        for (StateResponse state : stateService.getStates(slug, projectId)) {
            if (state.group() == StateGroup.UNSTARTED) {
                todo = state.id();
            } else if (state.group() == StateGroup.STARTED && started == null) {
                started = state.id();
            } else if (state.group() == StateGroup.COMPLETED) {
                done = state.id();
            }
        }
        installationId = IDS.incrementAndGet();
        repositoryGithubId = IDS.incrementAndGet();
        repositoryName = "acme-" + suffix + "/web";
        GITHUB.reset(installationId, repositoryGithubId, repositoryName);
    }

    // ----- Conectar

    @Test
    void conectarGuardaLaInstalacionYSusRepositorios() {
        assertThat(githubService.workspace(slug).connected()).isFalse();
        assertThat(githubService.workspace(slug).configured()).isTrue();

        String url = githubService.installUrl(slug, pm.getId());
        assertThat(url).startsWith("https://github.test/apps/taskflow-pruebas/installations/new?state=");

        String back = githubService.completeSetup(installationId, state(url));

        assertThat(back).isEqualTo("http://localhost:5173/" + slug + "/settings?tab=integrations&github=connected");
        WorkspaceStatus status = githubService.workspace(slug);
        assertThat(status.connected()).isTrue();
        assertThat(status.accountLogin()).isEqualTo("acme");
        assertThat(status.accountType()).isEqualTo("Organization");
        assertThat(status.installedByName()).isEqualTo("Paula PM");
        assertThat(status.manageUrl()).isEqualTo("https://github.test/organizations/acme/settings/installations/" + installationId);
        assertThat(status.repositories()).singleElement().satisfies(repository -> {
            assertThat(repository.fullName()).isEqualTo(repositoryName);
            assertThat(repository.defaultBranch()).isEqualTo("main");
            assertThat(repository.isPrivate()).isTrue();
            assertThat(repository.projects()).isEmpty();
        });
        // A GitHub se le habló como la app (un JWT firmado con su clave) y luego con el token de la instalación
        assertThat(GITHUB.requests()).contains("GET /app/installations/" + installationId,
                "POST /app/installations/" + installationId + "/access_tokens");
    }

    @Test
    void unEnlaceDeInstalacionManipuladoOAjenoNoConecta() {
        String state = state(githubService.installUrl(slug, pm.getId()));

        // Otro workspace metido a mano en el enlace: la firma ya no corresponde
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (UUID.randomUUID() + "|" + pm.getId() + "|" + (Instant.now().getEpochSecond() + 600)).getBytes(StandardCharsets.UTF_8))
                + state.substring(state.indexOf('.'));
        assertThat(githubService.completeSetup(installationId, forged)).endsWith("github=invalid");
        assertThat(githubService.completeSetup(installationId, "sin-firma")).endsWith("github=invalid");
        assertThat(githubService.workspace(slug).connected()).isFalse();

        // La instalación de un workspace no se la puede quedar otro
        assertThat(githubService.completeSetup(installationId, state)).endsWith("github=connected");
        User other = user("otra-" + suffix + "@test.com", "Olga Otra");
        String otherSlug = workspaceService.create(other.getId(), new CreateWorkspaceRequest("Otro " + suffix, "go-" + suffix)).slug();
        assertThat(githubService.completeSetup(installationId, state(githubService.installUrl(otherSlug, other.getId()))))
                .endsWith("github=taken");
        assertThat(githubService.workspace(otherSlug).connected()).isFalse();
        assertThat(githubService.workspace(slug).connected()).isTrue();
    }

    @Test
    void unaInstalacionViejaQueNadieReclamoNoSeConecta() {
        // Alguien con un enlace válido de su workspace que prueba con el número de una instalación que no acaba de hacer
        GITHUB.installedAt(Instant.now().minusSeconds(86_400));

        assertThat(githubService.completeSetup(installationId, state(githubService.installUrl(slug, pm.getId())))).endsWith("github=failed");
        assertThat(githubService.workspace(slug).connected()).isFalse();
    }

    @Test
    void desconectarQuitaLaAppYLoGuardado() {
        connect();

        githubService.disconnect(slug);

        assertThat(githubService.workspace(slug).connected()).isFalse();
        assertThat(GITHUB.requests()).contains("DELETE /app/installations/" + installationId);
        assertThat(githubService.project(slug, projectId).repositories()).isEmpty();
    }

    // ----- Avisos

    @Test
    void soloSeAtiendenLosAvisosFirmados() {
        connect();
        IssueResponse task = task("Login");
        byte[] body = json(branchEvent("create", branch(task)));

        assertThat(webhookController.webhook(body, "sha256=" + "0".repeat(64), "create", "d-" + suffix).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(webhookController.webhook(body, null, "create", "d-" + suffix).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(githubService.issue(slug, projectId, task.id()).branches()).isEmpty();

        assertThat(webhookController.webhook(body, signature(body), "create", "d-" + suffix).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(githubService.issue(slug, projectId, task.id()).branches()).extracting("name").containsExactly(branch(task));
    }

    @Test
    void laTareaSigueASuRamaYASuPullRequest() {
        connect();
        githubService.saveSettings(slug, projectId, new Settings(started, null, done, todo));
        IssueResponse task = task("Rotación del token");
        String branch = branch(task);

        // La rama nombra a la tarea: queda enlazada y la tarea empieza
        send("create", branchEvent("create", branch));
        assertThat(group(task)).isEqualTo("STARTED");
        assertThat(githubService.issue(slug, projectId, task.id()).branches()).extracting("name").containsExactly(branch);

        // Se abre el pull request (sin automatización para eso: la tarea sigue donde está)
        send("pull_request", pullEvent("opened", 12, "Rota el token", branch, "open", false, false));
        assertThat(group(task)).isEqualTo("STARTED");
        PullRequest open = githubService.issue(slug, projectId, task.id()).pullRequests().getFirst();
        assertThat(open.number()).isEqualTo(12);
        assertThat(open.state()).isEqualTo("OPEN");
        assertThat(open.issueCode()).isEqualTo(identifier + "-" + task.sequenceId());
        assertThat(open.reviewers()).isEqualTo("marta");
        assertThat(githubService.pullRequests(slug, projectId, "open")).extracting(PullRequest::number).containsExactly(12);
        assertThat(githubService.summary(slug, projectId)).singleElement().satisfies(summary -> {
            assertThat(summary.issueId()).isEqualTo(task.id());
            assertThat(summary.branch()).isEqualTo(branch);
            assertThat(summary.prNumber()).isEqualTo(12);
            assertThat(summary.prState()).isEqualTo("OPEN");
        });

        // Se fusiona: la tarea se completa
        send("pull_request", pullEvent("closed", 12, "Rota el token", branch, "closed", true, false));
        assertThat(group(task)).isEqualTo("COMPLETED");
        assertThat(githubService.pullRequests(slug, projectId, "open")).isEmpty();
        assertThat(githubService.pullRequests(slug, projectId, "merged")).extracting(PullRequest::state).containsExactly("MERGED");

        // El historial de la tarea lo cuenta, sin autor
        assertThat(values("SELECT a.field || ':' || coalesce(a.new_value, '') FROM issue_activities a WHERE a.issue_id = '" + task.id()
                + "' AND a.actor_id IS NULL ORDER BY a.created_at, a.field")).contains("GITHUB:BRANCH|" + branch, "GITHUB:PR_OPENED|12|luis|Rota el token",
                "GITHUB:PR_MERGED|12|luis|Rota el token");
    }

    @Test
    void unPullRequestCerradoSinFusionarDevuelveLaTareaYUnBorradorNoLaMueve() {
        connect();
        githubService.saveSettings(slug, projectId, new Settings(null, started, done, todo));
        IssueResponse task = task("Exportar");

        // Un borrador que nombra a la tarea en el título queda enlazado, pero no la mueve
        send("pull_request", pullEvent("opened", 5, identifier + "-" + task.sequenceId() + " Exportar", "rama-sin-codigo", "open", false, true));
        assertThat(group(task)).isEqualTo("UNSTARTED");
        assertThat(githubService.issue(slug, projectId, task.id()).pullRequests()).extracting(PullRequest::state).containsExactly("DRAFT");

        // Listo para revisar: ahora sí
        send("pull_request", pullEvent("ready_for_review", 5, identifier + "-" + task.sequenceId() + " Exportar", "rama-sin-codigo", "open", false, false));
        assertThat(group(task)).isEqualTo("STARTED");

        // Cerrado sin fusionar: vuelve a por hacer
        send("pull_request", pullEvent("closed", 5, identifier + "-" + task.sequenceId() + " Exportar", "rama-sin-codigo", "closed", false, false));
        assertThat(group(task)).isEqualTo("UNSTARTED");
    }

    @Test
    void unaTareaTerminadaNoSeReabrePorUnPullRequestNuevo() {
        connect();
        githubService.saveSettings(slug, projectId, new Settings(started, started, done, todo));
        IssueResponse task = task("Ya hecha");
        issueService.move(slug, projectId, task.id(), pm.getId(), new MoveIssueRequest(done, null, null));

        send("create", branchEvent("create", branch(task)));
        send("pull_request", pullEvent("opened", 9, "Retoque", branch(task), "open", false, false));

        assertThat(group(task)).isEqualTo("COMPLETED");
    }

    @Test
    void unAvisoRepetidoNoSeAtiendeDosVeces() {
        connect();
        IssueResponse task = task("Repetido");
        byte[] body = json(pullEvent("opened", 3, "Algo", branch(task), "open", false, false));

        webhookController.webhook(body, signature(body), "pull_request", "igual-" + suffix);
        webhookController.webhook(body, signature(body), "pull_request", "igual-" + suffix);

        assertThat(values("SELECT a.new_value FROM issue_activities a WHERE a.issue_id = '" + task.id() + "' AND a.field = 'GITHUB' AND a.new_value LIKE 'PR_OPENED%'"))
                .hasSize(1);
    }

    @Test
    void unPullRequestDeUnRepositorioQueNoEsDelProyectoNoTocaSusTareas() {
        connect();
        githubService.setRepositories(slug, projectId, List.of());
        IssueResponse task = task("Ajena");

        send("pull_request", pullEvent("opened", 4, "Algo", branch(task), "open", false, false));

        assertThat(githubService.issue(slug, projectId, task.id()).pullRequests()).isEmpty();
        assertThat(githubService.pullRequests(slug, projectId, "all")).isEmpty();
    }

    // ----- Actions

    @Test
    void alFusionarElPullRequestSeEjecutaElWorkflowDeLaRegla() {
        UUID repositoryId = connect();
        githubService.saveSettings(slug, projectId, new Settings(null, null, done, null));
        githubService.addRule(slug, projectId, pm.getId(), GithubService.FIRES_ON_MERGE, repositoryId, 77, "Deploy", "main");
        IssueResponse task = task("Con despliegue");

        send("pull_request", pullEvent("closed", 21, "Listo", branch(task), "closed", true, false));

        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/actions/workflows/77/dispatches")).singleElement()
                .satisfies(body -> assertThat(body.path("ref").asString()).isEqualTo("main"));
        Run requested = githubService.runs(slug, projectId).getFirst();
        assertThat(requested.runId()).isNull();
        assertThat(requested.workflowName()).isEqualTo("Deploy");
        assertThat(requested.issueId()).isEqualTo(task.id());
        assertThat(requested.ruleId()).isNotNull();

        // GitHub avisa de la ejecución: es la que TaskFlow había pedido, no una nueva
        send("workflow_run", runEvent("requested", 9001, 77, "Deploy", "main", "workflow_dispatch", "queued", null));
        send("workflow_run", runEvent("completed", 9001, 77, "Deploy", "main", "workflow_dispatch", "completed", "success"));

        assertThat(githubService.runs(slug, projectId)).singleElement().satisfies(run -> {
            assertThat(run.runId()).isEqualTo(9001L);
            assertThat(run.status()).isEqualTo("completed");
            assertThat(run.conclusion()).isEqualTo("success");
            assertThat(run.issueId()).isEqualTo(task.id());
            assertThat(run.ruleId()).isNotNull();
        });
        assertThat(githubService.issue(slug, projectId, task.id()).runs()).hasSize(1);
    }

    @Test
    void alCompletarUnaTareaAManoSeEjecutaLaReglaDeTareaCompletada() {
        UUID repositoryId = connect();
        githubService.addRule(slug, projectId, pm.getId(), GithubService.FIRES_ON_COMPLETED, repositoryId, 88, "Notificar", "main");
        IssueResponse task = task("A mano");

        issueService.move(slug, projectId, task.id(), pm.getId(), new MoveIssueRequest(started, null, null));
        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/actions/workflows/88/dispatches")).isEmpty();

        issueService.move(slug, projectId, task.id(), pm.getId(), new MoveIssueRequest(done, null, null));

        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/actions/workflows/88/dispatches")).hasSize(1);
        assertThat(githubService.runs(slug, projectId)).singleElement().satisfies(run -> assertThat(run.issueId()).isEqualTo(task.id()));
    }

    @Test
    void siGitHubRechazaElWorkflowDeUnaReglaLaTareaIgualSeCompleta() {
        UUID repositoryId = connect();
        githubService.addRule(slug, projectId, pm.getId(), GithubService.FIRES_ON_COMPLETED, repositoryId, 99, "Roto", "main");
        GITHUB.fail("POST /repos/" + repositoryName + "/actions/workflows/99/dispatches", 422);
        IssueResponse task = task("Sigue");

        issueService.move(slug, projectId, task.id(), pm.getId(), new MoveIssueRequest(done, null, null));

        assertThat(group(task)).isEqualTo("COMPLETED");
        assertThat(githubService.runs(slug, projectId)).isEmpty();
    }

    @Test
    void ejecutarUnWorkflowAManoYVerSusDatos() {
        UUID repositoryId = connect();

        assertThat(githubService.workflows(slug, projectId, repositoryId)).singleElement().satisfies(workflow -> {
            assertThat(workflow.id()).isEqualTo(77);
            assertThat(workflow.name()).isEqualTo("Deploy");
            assertThat(workflow.inputs()).containsExactly("entorno");
        });
        assertThat(githubService.branches(slug, projectId, repositoryId)).containsExactly("main", "develop");

        Run run = githubService.dispatch(slug, projectId, pm.getId(), repositoryId, 77, "Deploy", "develop", Map.of("entorno", "staging"), null);

        assertThat(run.runId()).isNull();
        assertThat(run.triggeredByName()).isEqualTo("Paula PM");
        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/actions/workflows/77/dispatches")).singleElement().satisfies(body -> {
            assertThat(body.path("ref").asString()).isEqualTo("develop");
            assertThat(body.path("inputs").path("entorno").asString()).isEqualTo("staging");
        });

        // Todavía no empezó en GitHub: no hay qué repetir ni cancelar
        assertThatThrownBy(() -> githubService.rerun(slug, projectId, run.id())).isInstanceOf(ConflictException.class);

        send("workflow_run", runEvent("in_progress", 9100, 77, "Deploy", "develop", "workflow_dispatch", "in_progress", null));
        githubService.cancel(slug, projectId, run.id());
        assertThat(GITHUB.requests()).contains("POST /repos/" + repositoryName + "/actions/runs/9100/cancel");
    }

    @Test
    void unaEjecucionPedidaQueGitHubNuncaAnuncioDejaDeVerse() {
        UUID repositoryId = connect();
        IssueResponse task = task("Sin respuesta");
        send("pull_request", pullEvent("closed", 50, "Sin respuesta", branch(task), "closed", true, false));
        githubService.dispatch(slug, projectId, pm.getId(), repositoryId, 77, "Deploy", "main", Map.of(), task.id());
        assertThat(githubService.runs(slug, projectId)).hasSize(1);
        assertThat(githubService.summary(slug, projectId)).singleElement().satisfies(summary -> assertThat(summary.running()).isTrue());

        // A los diez minutos ya no cuenta como «desplegando»; a la hora deja de listarse
        sql("UPDATE github_workflow_runs SET requested_at = now() - interval '11 minutes' WHERE issue_id = '" + task.id() + "'");
        assertThat(githubService.summary(slug, projectId)).singleElement().satisfies(summary -> assertThat(summary.running()).isFalse());
        assertThat(githubService.runs(slug, projectId)).hasSize(1);

        sql("UPDATE github_workflow_runs SET requested_at = now() - interval '61 minutes' WHERE issue_id = '" + task.id() + "'");
        assertThat(githubService.runs(slug, projectId)).isEmpty();
        assertThat(githubService.issue(slug, projectId, task.id()).runs()).isEmpty();
    }

    @Test
    void siGitHubNoDejaEjecutarSeExplicaYNoQuedaUnaEjecucionColgada() {
        UUID repositoryId = connect();
        GITHUB.fail("POST /repos/" + repositoryName + "/actions/workflows/77/dispatches", 403);

        assertThatThrownBy(() -> githubService.dispatch(slug, projectId, pm.getId(), repositoryId, 77, "Deploy", "main", Map.of(), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("permiso");
        assertThat(githubService.runs(slug, projectId)).isEmpty();
    }

    @Test
    void losChecksDelPullRequestSeCuentanAlTerminar() {
        connect();
        IssueResponse task = task("Con checks");
        send("pull_request", pullEvent("opened", 30, "Con checks", branch(task), "open", false, false));

        send("check_suite", suiteEvent("requested"));
        assertThat(githubService.issue(slug, projectId, task.id()).pullRequests().getFirst().checksState()).isEqualTo("PENDING");

        send("check_suite", suiteEvent("completed"));
        PullRequest pull = githubService.issue(slug, projectId, task.id()).pullRequests().getFirst();
        assertThat(pull.checksState()).isEqualTo("FAILURE");
        assertThat(pull.checksTotal()).isEqualTo(3);
        assertThat(pull.checksFailed()).isEqualTo(1);
        assertThat(githubService.checks(slug, projectId, task.id(), pull.id())).extracting("name").containsExactly("build", "test", "lint");
    }

    @Test
    void alFallarLosChecksQuedaEnLaActividadDeLaTareaUnaSolaVez() {
        connect();
        IssueResponse task = task("Con checks rotos");
        send("pull_request", pullEvent("opened", 31, "Con checks rotos", branch(task), "open", false, false));

        send("check_suite", suiteEvent("completed"));
        send("check_suite", suiteEvent("completed"));

        assertThat(values("SELECT a.new_value FROM issue_activities a WHERE a.issue_id = '" + task.id()
                + "' AND a.field = 'GITHUB' AND a.new_value LIKE 'CHECKS_FAILED%'")).containsExactly("CHECKS_FAILED|31|test");
    }

    @Test
    void siFaltanCriteriosDeAceptacionLaTareaNoSeCompletaSolaYSeAvisaAQuienLaTiene() {
        connect();
        githubService.saveSettings(slug, projectId, new Settings(null, null, done, null));
        IssueResponse task = issueService.create(slug, projectId, pm.getId(), new CreateIssueRequest("Con criterios", null, todo, null, null,
                null, null, List.of(pm.getId()), null, List.of(new ChecklistItemRequest(ChecklistItemKind.CRITERION, "Funciona sin conexión")),
                null, null, null, null));

        send("pull_request", pullEvent("closed", 40, "Con criterios", branch(task), "closed", true, false));

        assertThat(group(task)).isEqualTo("UNSTARTED");
        assertThat(values("SELECT n.message FROM notifications n WHERE n.issue_id = '" + task.id() + "' AND n.type = 'PR_MERGED_PENDING'"
                + " AND n.recipient_id = '" + pm.getId() + "' AND n.actor_id IS NULL")).singleElement().asString()
                .contains("#40").contains(identifier + "-" + task.sequenceId());
    }

    @Test
    void elCambioDeEstadoAutomaticoQuedaComoDeGitHub() {
        connect();
        githubService.saveSettings(slug, projectId, new Settings(started, null, null, null));
        IssueResponse task = task("Automática");

        send("create", branchEvent("create", branch(task)));

        assertThat(values("SELECT a.field FROM issue_activities a WHERE a.issue_id = '" + task.id() + "' AND a.actor_id IS NULL"))
                .containsExactlyInAnyOrder("GITHUB", "GITHUB_STATE");
    }

    @Test
    void laReglaRecuerdaSuUltimaEjecucion() {
        UUID repositoryId = connect();
        githubService.addRule(slug, projectId, pm.getId(), GithubService.FIRES_ON_COMPLETED, repositoryId, 88, "Notificar", "main");
        assertThat(githubService.project(slug, projectId).rules()).singleElement().satisfies(rule -> assertThat(rule.lastRunAt()).isNull());

        issueService.move(slug, projectId, task("Dispara").id(), pm.getId(), new MoveIssueRequest(done, null, null));
        send("workflow_run", runEvent("completed", 9300, 88, "Notificar", "main", "workflow_dispatch", "completed", "failure"));

        assertThat(githubService.project(slug, projectId).rules()).singleElement().satisfies(rule -> {
            assertThat(rule.lastRunAt()).isNotNull();
            assertThat(rule.lastRunStatus()).isEqualTo("completed");
            assertThat(rule.lastRunConclusion()).isEqualTo("failure");
        });
    }

    // ----- La rama de la tarea

    @Test
    void elNombreDeLaRamaSigueElFormatoDelProyecto() {
        connect();
        IssueResponse task = task("Añadir exportación");
        String code = identifier.toLowerCase() + "-" + task.sequenceId();

        githubService.saveSettings(slug, projectId, new Settings(null, null, null, null, "TYPE_CODE_TITLE"));
        assertThat(githubService.issue(slug, projectId, task.id()).suggestedBranch()).isEqualTo("feature/" + code + "-anadir-exportacion");

        githubService.saveSettings(slug, projectId, new Settings(null, null, null, null, "CODE"));
        assertThat(githubService.issue(slug, projectId, task.id()).suggestedBranch()).isEqualTo(code);
        assertThat(githubService.project(slug, projectId).settings().branchFormat()).isEqualTo("CODE");

        // Uno que no existe se queda en el de siempre
        githubService.saveSettings(slug, projectId, new Settings(null, null, null, null, "OTRO"));
        assertThat(githubService.project(slug, projectId).settings().branchFormat()).isEqualTo("CODE_TITLE");
    }

    @Test
    void enlazarAManoUnaRamaQueNoNombraALaTarea() {
        UUID repositoryId = connect();
        githubService.saveSettings(slug, projectId, new Settings(started, null, null, null));
        IssueResponse task = task("Sin código en la rama");

        IssueDev dev = githubService.linkBranch(slug, projectId, task.id(), repositoryId, "main");

        assertThat(dev.branches()).extracting("name").containsExactly("main");
        // Enlazarla a mano no es empezar la tarea
        assertThat(group(task)).isEqualTo("UNSTARTED");
        assertThat(githubService.linkedBranches(slug, projectId)).singleElement().satisfies(branch -> {
            assertThat(branch.issueCode()).isEqualTo(identifier + "-" + task.sequenceId());
            assertThat(branch.issueTitle()).isEqualTo("Sin código en la rama");
        });
        assertThatThrownBy(() -> githubService.linkBranch(slug, projectId, task.id(), repositoryId, "no-existe"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void crearLaRamaSinMoverLaTarea() {
        UUID repositoryId = connect();
        githubService.saveSettings(slug, projectId, new Settings(started, null, null, null));
        IssueResponse task = task("Todavía no");

        githubService.createBranch(slug, projectId, task.id(), repositoryId, "main", "rama-" + suffix, false);

        assertThat(group(task)).isEqualTo("UNSTARTED");
    }

    @Test
    void crearLaRamaDeUnaTareaDesdeTaskFlow() {
        UUID repositoryId = connect();
        githubService.saveSettings(slug, projectId, new Settings(started, null, null, null));
        IssueResponse task = task("Añadir exportación");

        IssueDev before = githubService.issue(slug, projectId, task.id());
        assertThat(before.available()).isTrue();
        assertThat(before.suggestedBranch()).isEqualTo(identifier.toLowerCase() + "-" + task.sequenceId() + "-anadir-exportacion");

        IssueDev after = githubService.createBranch(slug, projectId, task.id(), repositoryId, null, before.suggestedBranch(), true);

        assertThat(after.branches()).extracting("name").containsExactly(before.suggestedBranch());
        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/git/refs")).singleElement().satisfies(body -> {
            assertThat(body.path("ref").asString()).isEqualTo("refs/heads/" + before.suggestedBranch());
            assertThat(body.path("sha").asString()).isEqualTo("sha-de-main");
        });
        assertThat(group(task)).isEqualTo("STARTED");

        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, task.id(), repositoryId, "main", "con espacio", true))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, task.id(), repositoryId, "no-existe", "otra", true))
                .isInstanceOf(BadRequestException.class);
        GITHUB.fail("POST /repos/" + repositoryName + "/git/refs", 422);
        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, task.id(), repositoryId, "main", "repetida", true))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void laVueltaDeInstalarLlevaALosAjustes() {
        ResponseEntity<Void> response = webhookController.setup(installationId, state(githubService.installUrl(slug, pm.getId())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("http://localhost:5173/" + slug + "/settings?tab=integrations&github=connected");
        // Sin enlace firmado (un cambio hecho desde GitHub) no hay nada que guardar
        assertThat(webhookController.setup(installationId, null).getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("http://localhost:5173/");
    }

    // ----- Ayudas

    /** Conecta el workspace y enlaza el repositorio al proyecto. */
    private UUID connect() {
        assertThat(githubService.completeSetup(installationId, state(githubService.installUrl(slug, pm.getId())))).endsWith("github=connected");
        UUID repositoryId = githubService.workspace(slug).repositories().getFirst().id();
        ProjectStatus status = githubService.setRepositories(slug, projectId, List.of(repositoryId));
        assertThat(status.repositories()).hasSize(1);
        return repositoryId;
    }

    private static String state(String installUrl) {
        return installUrl.substring(installUrl.indexOf("state=") + "state=".length());
    }

    private IssueResponse task(String title) {
        return issueService.create(slug, projectId, pm.getId(),
                new CreateIssueRequest(title, null, todo, null, null, null, null, null, null, null, null, null, null, null));
    }

    private String branch(IssueResponse task) {
        return identifier.toLowerCase() + "-" + task.sequenceId() + "-trabajo";
    }

    private String group(IssueResponse task) {
        return values("SELECT s.state_group FROM issues i JOIN states s ON s.id = i.state_id WHERE i.id = '" + task.id() + "'").getFirst();
    }

    private Map<String, Object> envelope() {
        return Map.of("installation", Map.of("id", installationId), "repository", Map.of("id", repositoryGithubId, "full_name", repositoryName));
    }

    private Map<String, Object> with(Map<String, Object> more) {
        Map<String, Object> event = new java.util.LinkedHashMap<>(envelope());
        event.putAll(more);
        return event;
    }

    private Map<String, Object> branchEvent(String kind, String branch) {
        return with(Map.of("ref", branch, "ref_type", "branch"));
    }

    private Map<String, Object> pullEvent(String action, int number, String title, String head, String state, boolean merged, boolean draft) {
        Map<String, Object> pull = new java.util.LinkedHashMap<>();
        pull.put("number", number);
        pull.put("title", title);
        pull.put("body", "");
        pull.put("state", state);
        pull.put("draft", draft);
        pull.put("merged", merged);
        if (merged) {
            pull.put("merged_at", "2026-11-10T10:00:00Z");
        }
        pull.put("html_url", "https://github.test/" + repositoryName + "/pull/" + number);
        pull.put("user", Map.of("login", "luis"));
        pull.put("head", Map.of("ref", head, "sha", "sha-del-pr"));
        pull.put("base", Map.of("ref", "main"));
        pull.put("requested_reviewers", List.of(Map.of("login", "marta")));
        pull.put("created_at", "2026-11-09T09:00:00Z");
        pull.put("updated_at", "2026-11-10T10:00:00Z");
        return with(Map.of("action", action, "pull_request", pull));
    }

    private Map<String, Object> runEvent(String action, long runId, long workflowId, String name, String branch, String event, String status,
                                         String conclusion) {
        Map<String, Object> run = new java.util.LinkedHashMap<>();
        run.put("id", runId);
        run.put("workflow_id", workflowId);
        run.put("name", name);
        run.put("display_title", name);
        run.put("head_branch", branch);
        run.put("head_sha", "sha-de-la-ejecucion");
        run.put("event", event);
        run.put("status", status);
        run.put("conclusion", conclusion);
        run.put("actor", Map.of("login", "taskflow[bot]"));
        run.put("html_url", "https://github.test/" + repositoryName + "/actions/runs/" + runId);
        run.put("pull_requests", List.of());
        run.put("run_started_at", "2026-11-10T10:01:00Z");
        run.put("updated_at", "2026-11-10T10:02:00Z");
        return with(Map.of("action", action, "workflow_run", run));
    }

    private Map<String, Object> suiteEvent(String action) {
        return with(Map.of("action", action, "check_suite", Map.of("head_sha", "sha-del-pr")));
    }

    private void send(String event, Map<String, Object> payload) {
        byte[] body = json(payload);
        assertThat(webhookController.webhook(body, signature(body), event, UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private static byte[] json(Object value) {
        return JSON.writeValueAsBytes(value);
    }

    private static String signature(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private User user(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode("Clave1234"));
        user.setEmailVerified(true);
        return userRepository.saveAndFlush(user);
    }

    private void sql(String statement) {
        try (Connection connection = TestDatabase.connect();
             PreparedStatement query = connection.prepareStatement(statement)) {
            query.execute();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<String> values(String query) {
        try (Connection connection = TestDatabase.connect();
             PreparedStatement statement = connection.prepareStatement(query);
             ResultSet rows = statement.executeQuery()) {
            List<String> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Un GitHub de mentira: responde lo justo de su API y recuerda lo que se le pidió. */
    private static final class FakeGithub {

        private final HttpServer server;
        private final Path keyFile;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final List<Map.Entry<String, JsonNode>> bodies = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> failures = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile long installationId;
        private volatile long repositoryId;
        private volatile String repository;
        private volatile Instant installedAt;

        FakeGithub() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                // Como la entrega GitHub: PKCS#1 (lo que queda tras la cabecera de PKCS#8)
                byte[] pkcs8 = pair.getPrivate().getEncoded();
                keyFile = Files.createTempFile("taskflow-github-", ".pem");
                keyFile.toFile().deleteOnExit();
                Files.writeString(keyFile, "-----BEGIN RSA PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder().encodeToString(Arrays.copyOfRange(pkcs8, 26, pkcs8.length)) + "\n-----END RSA PRIVATE KEY-----\n");
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", this::handle);
                server.start();
            } catch (IOException | java.security.GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        }

        Path keyFile() {
            return keyFile;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset(long installationId, long repositoryId, String repository) {
            this.installationId = installationId;
            this.repositoryId = repositoryId;
            this.repository = repository;
            this.installedAt = Instant.now();
            requests.clear();
            bodies.clear();
            failures.clear();
        }

        void installedAt(Instant when) {
            installedAt = when;
        }

        void fail(String request, int status) {
            failures.put(request, status);
        }

        List<String> requests() {
            return requests;
        }

        List<JsonNode> bodies(String request) {
            return bodies.stream().filter(entry -> entry.getKey().equals(request)).map(Map.Entry::getValue).toList();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String request = exchange.getRequestMethod() + " " + path;
            byte[] received = exchange.getRequestBody().readAllBytes();
            requests.add(request);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            Integer failure = failures.get(request);
            int status = 200;
            Object response;
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                status = 401;
                response = Map.of("message", "sin credenciales");
            } else if (failure != null) {
                status = failure;
                response = Map.of("message", "rechazado");
            } else {
                if (received.length > 0) {
                    bodies.add(Map.entry(request, JSON.readTree(received)));
                }
                String repo = "/repos/" + repository;
                if (request.equals("GET /app/installations/" + installationId)) {
                    response = Map.of("id", installationId, "account", Map.of("login", "acme", "type", "Organization"),
                            "created_at", installedAt.toString(), "updated_at", installedAt.toString());
                } else if (request.equals("POST /app/installations/" + installationId + "/access_tokens")) {
                    status = 201;
                    response = Map.of("token", "token-de-instalacion", "expires_at", Instant.now().plusSeconds(3600).toString());
                } else if (request.equals("DELETE /app/installations/" + installationId)) {
                    status = 204;
                    response = null;
                } else if (request.equals("GET /installation/repositories")) {
                    response = Map.of("total_count", 1, "repositories",
                            List.of(Map.of("id", repositoryId, "full_name", repository, "default_branch", "main", "private", true)));
                } else if (request.equals("GET " + repo + "/pulls")) {
                    response = List.of();
                } else if (request.equals("GET " + repo + "/actions/runs")) {
                    response = Map.of("workflow_runs", List.of());
                } else if (request.equals("GET " + repo + "/branches")) {
                    response = List.of(Map.of("name", "main"), Map.of("name", "develop"));
                } else if (request.equals("GET " + repo + "/git/ref/heads/main")) {
                    response = Map.of("object", Map.of("sha", "sha-de-main"));
                } else if (request.equals("POST " + repo + "/git/refs")) {
                    status = 201;
                    response = Map.of("ref", "creada");
                } else if (request.equals("GET " + repo + "/actions/workflows")) {
                    response = Map.of("workflows", List.of(
                            Map.of("id", 77, "name", "Deploy", "path", ".github/workflows/deploy.yml", "state", "active"),
                            Map.of("id", 78, "name", "CI", "path", ".github/workflows/ci.yml", "state", "active"),
                            Map.of("id", 79, "name", "Viejo", "path", ".github/workflows/viejo.yml", "state", "disabled_manually")));
                } else if (request.equals("GET " + repo + "/contents/.github/workflows/deploy.yml")) {
                    response = Map.of("content", Base64.getMimeEncoder().encodeToString(
                            "on:\n  workflow_dispatch:\n    inputs:\n      entorno:\n        required: true\njobs: {}\n".getBytes(StandardCharsets.UTF_8)));
                } else if (request.equals("GET " + repo + "/contents/.github/workflows/ci.yml")) {
                    response = Map.of("content", Base64.getMimeEncoder().encodeToString("on: [push]\njobs: {}\n".getBytes(StandardCharsets.UTF_8)));
                } else if (path.startsWith(repo + "/actions/workflows/") && path.endsWith("/dispatches")) {
                    status = 204;
                    response = null;
                } else if (path.startsWith(repo + "/actions/runs/")) {
                    status = 201;
                    response = Map.of();
                } else if (request.equals("GET " + repo + "/commits/sha-del-pr/check-runs")) {
                    response = Map.of("check_runs", List.of(
                            Map.of("name", "build", "status", "completed", "conclusion", "success"),
                            Map.of("name", "test", "status", "completed", "conclusion", "failure"),
                            Map.of("name", "lint", "status", "completed", "conclusion", "skipped")));
                } else {
                    status = 404;
                    response = Map.of("message", "Not Found");
                }
            }
            try {
                byte[] out = response == null ? new byte[0] : JSON.writeValueAsBytes(response);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    exchange.getResponseBody().write(out);
                }
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            } finally {
                exchange.close();
            }
        }
    }
}
