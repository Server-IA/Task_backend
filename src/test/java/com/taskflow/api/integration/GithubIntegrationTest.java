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
import com.taskflow.api.github.GithubAccounts;
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
        // Lo que la integración hace después de confirmar (contar checks, ejecutar reglas) va en el mismo hilo: así las
        // pruebas ven el resultado al volver de la llamada
        registry.add("app.github.inline", () -> "true");
        registry.add("app.github.client-id", () -> "Iv1.pruebas");
        registry.add("app.github.client-secret", () -> "secreto-de-cliente");
        registry.add("app.github.web-url", GITHUB::url);
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
    @Autowired
    private GithubAccounts accounts;

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
    /** El usuario de GitHub de la PM (enlazado en cada prueba). */
    private String pmLogin;

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
        pmLogin = "paula-" + suffix;
        link(pm, pmLogin);
    }

    // ----- Conectar

    @Test
    void conectarGuardaLaInstalacionYSusRepositorios() {
        assertThat(githubService.workspace(slug).connected()).isFalse();
        assertThat(githubService.workspace(slug).configured()).isTrue();

        String url = githubService.installUrl(slug, pm.getId());
        assertThat(url).startsWith(GITHUB.url() + "/apps/taskflow-pruebas/installations/new?state=");

        String back = githubService.completeSetup(installationId, state(url));

        assertThat(back).isEqualTo("http://localhost:5173/" + slug + "/settings?tab=integrations&github=connected");
        WorkspaceStatus status = githubService.workspace(slug);
        assertThat(status.connected()).isTrue();
        assertThat(status.accountLogin()).isEqualTo("acme");
        assertThat(status.accountType()).isEqualTo("Organization");
        assertThat(status.installedByName()).isEqualTo("Paula PM");
        assertThat(status.manageUrl()).isEqualTo(GITHUB.url() + "/organizations/acme/settings/installations/" + installationId);
        // Se comprobó con la cuenta de quien conecta que la instalación es suya
        assertThat(GITHUB.asUser()).contains("GET /user/installations como " + pmLogin);
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
        link(other, "olga-" + suffix);
        String otherSlug = workspaceService.create(other.getId(), new CreateWorkspaceRequest("Otro " + suffix, "go-" + suffix)).slug();
        assertThat(githubService.completeSetup(installationId, state(githubService.installUrl(otherSlug, other.getId()))))
                .endsWith("github=taken");
        assertThat(githubService.workspace(otherSlug).connected()).isFalse();
        assertThat(githubService.workspace(slug).connected()).isTrue();
    }

    @Test
    void unaInstalacionQueLaCuentaDeQuienConectaNoVeNoSeConecta() {
        // Alguien con un enlace válido de su workspace que prueba con el número de la instalación de otra organización
        GITHUB.outsider(pmLogin);

        assertThat(githubService.completeSetup(installationId, state(githubService.installUrl(slug, pm.getId())))).endsWith("github=foreign");
        assertThat(githubService.workspace(slug).connected()).isFalse();
    }

    @Test
    void paraConectarElWorkspaceHayQueTenerLaCuentaEnlazada() {
        String state = state(githubService.installUrl(slug, pm.getId()));
        accounts.unlink(pm.getId());

        // Ni pedir el enlace ni usar uno que se pidió antes de desenlazar
        assertThatThrownBy(() -> githubService.installUrl(slug, pm.getId())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("Conecta tu cuenta de GitHub");
        assertThat(githubService.completeSetup(installationId, state)).endsWith("github=unlinked");
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
        assertThat(GITHUB.asUser()).noneMatch(request -> request.contains("/dispatches"));
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
        assertThatThrownBy(() -> githubService.rerun(slug, projectId, pm.getId(), run.id())).isInstanceOf(ConflictException.class);

        send("workflow_run", runEvent("in_progress", 9100, 77, "Deploy", "develop", "workflow_dispatch", "in_progress", null));
        githubService.cancel(slug, projectId, pm.getId(), run.id());
        assertThat(GITHUB.requests()).contains("POST /repos/" + repositoryName + "/actions/runs/9100/cancel");
        // Lo que pide una persona sale en GitHub con su cuenta, no con la de la app
        assertThat(GITHUB.asUser()).contains("POST /repos/" + repositoryName + "/actions/workflows/77/dispatches como " + pmLogin,
                "POST /repos/" + repositoryName + "/actions/runs/9100/cancel como " + pmLogin);
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

        IssueDev dev = githubService.linkBranch(slug, projectId, pm.getId(), task.id(), repositoryId, "main");

        assertThat(dev.branches()).extracting("name").containsExactly("main");
        // Enlazarla a mano no es empezar la tarea
        assertThat(group(task)).isEqualTo("UNSTARTED");
        assertThat(githubService.linkedBranches(slug, projectId)).singleElement().satisfies(branch -> {
            assertThat(branch.issueCode()).isEqualTo(identifier + "-" + task.sequenceId());
            assertThat(branch.issueTitle()).isEqualTo("Sin código en la rama");
        });
        assertThatThrownBy(() -> githubService.linkBranch(slug, projectId, pm.getId(), task.id(), repositoryId, "no-existe"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void crearLaRamaSinMoverLaTarea() {
        UUID repositoryId = connect();
        githubService.saveSettings(slug, projectId, new Settings(started, null, null, null));
        IssueResponse task = task("Todavía no");

        githubService.createBranch(slug, projectId, pm.getId(), task.id(), repositoryId, "main", "rama-" + suffix, false);

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

        IssueDev after = githubService.createBranch(slug, projectId, pm.getId(), task.id(), repositoryId, null, before.suggestedBranch(), true);

        assertThat(after.branches()).extracting("name").containsExactly(before.suggestedBranch());
        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/git/refs")).singleElement().satisfies(body -> {
            assertThat(body.path("ref").asString()).isEqualTo("refs/heads/" + before.suggestedBranch());
            assertThat(body.path("sha").asString()).isEqualTo("sha-de-main");
        });
        assertThat(group(task)).isEqualTo("STARTED");
        // La rama la crea la persona con su cuenta, y la actividad de la tarea dice que fue ella
        assertThat(GITHUB.asUser()).contains("POST /repos/" + repositoryName + "/git/refs como " + pmLogin);
        assertThat(values("SELECT a.actor_id FROM issue_activities a WHERE a.issue_id = '" + task.id() + "' AND a.field = 'GITHUB'"))
                .containsExactly(pm.getId().toString());

        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, pm.getId(), task.id(), repositoryId, "main", "con espacio", true))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, pm.getId(), task.id(), repositoryId, "no-existe", "otra", true))
                .isInstanceOf(BadRequestException.class);
        GITHUB.fail("POST /repos/" + repositoryName + "/git/refs", 422);
        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, pm.getId(), task.id(), repositoryId, "main", "repetida", true))
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

    // ----- Correcciones de la revisión

    @Test
    void unPullRequestQueAlguienDejoSinTareaNoSeVuelveAEnlazarNiRepiteLasReglas() {
        UUID repositoryId = connect();
        githubService.saveSettings(slug, projectId, new Settings(null, null, done, null));
        githubService.addRule(slug, projectId, pm.getId(), GithubService.FIRES_ON_MERGE, repositoryId, 77, "Deploy", "main");
        IssueResponse task = task("Enlazada por error");
        send("pull_request", pullEvent("closed", 70, "Algo", branch(task), "closed", true, false));
        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/actions/workflows/77/dispatches")).hasSize(1);
        UUID pullId = githubService.pullRequests(slug, projectId, "merged").getFirst().id();

        // Alguien lo deja sin tarea; después llega otro aviso del mismo pull request (una etiqueta, una edición)
        githubService.linkPullRequest(slug, projectId, pullId, null);
        send("pull_request", pullEvent("edited", 70, "Algo retocado", branch(task), "closed", true, false));

        assertThat(githubService.pullRequests(slug, projectId, "merged")).singleElement()
                .satisfies(pull -> assertThat(pull.issueId()).isNull());
        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/actions/workflows/77/dispatches")).hasSize(1);
        assertThat(values("SELECT a.new_value FROM issue_activities a WHERE a.issue_id = '" + task.id()
                + "' AND a.field = 'GITHUB' AND a.new_value LIKE 'PR_MERGED%'")).hasSize(1);
    }

    @Test
    void ponerElCodigoDeLaTareaEnUnPullRequestYaFusionadoNoLaCompletaNiEjecutaReglas() {
        UUID repositoryId = connect();
        githubService.saveSettings(slug, projectId, new Settings(null, started, done, null));
        githubService.addRule(slug, projectId, pm.getId(), GithubService.FIRES_ON_MERGE, repositoryId, 77, "Deploy", "main");
        IssueResponse merged = task("Nombrada después de fusionar");
        IssueResponse open = task("Nombrada con el PR abierto");
        send("pull_request", pullEvent("closed", 71, "Sin código", "rama-suelta-1", "closed", true, false));
        send("pull_request", pullEvent("opened", 72, "Sin código", "rama-suelta-2", "open", false, false));

        send("pull_request", pullEvent("edited", 71, identifier + "-" + merged.sequenceId() + " ya fusionado", "rama-suelta-1", "closed", true, false));
        send("pull_request", pullEvent("edited", 72, identifier + "-" + open.sequenceId() + " en revisión", "rama-suelta-2", "open", false, false));

        // El fusionado queda enlazado, pero no mueve la tarea ni despliega; el abierto sí cuenta como abrirlo
        assertThat(githubService.issue(slug, projectId, merged.id()).pullRequests()).hasSize(1);
        assertThat(group(merged)).isEqualTo("UNSTARTED");
        assertThat(GITHUB.bodies("POST /repos/" + repositoryName + "/actions/workflows/77/dispatches")).isEmpty();
        assertThat(group(open)).isEqualTo("STARTED");
    }

    @Test
    void unAvisoViejoQueLlegaTardeNoPisaLoMasNuevo() {
        connect();
        IssueResponse task = task("Desordenado");
        Map<String, Object> merged = pullEvent("closed", 73, "Desordenado", branch(task), "closed", true, false);
        Map<String, Object> opened = pullEvent("opened", 73, "Desordenado", branch(task), "open", false, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> older = (Map<String, Object>) opened.get("pull_request");
        older.put("updated_at", "2026-11-01T08:00:00Z");

        send("pull_request", merged);
        send("pull_request", opened);

        assertThat(githubService.issue(slug, projectId, task.id()).pullRequests()).singleElement()
                .satisfies(pull -> assertThat(pull.state()).isEqualTo("MERGED"));
    }

    @Test
    void conUnRepositorioCompartidoUnProyectoNoVeNiTocaLasTareasDelOtro() {
        UUID repositoryId = connect();
        String otherIdentifier = "H" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X");
        UUID otherProject = projectService.create(slug, pm.getId(), new CreateProjectRequest("Otro", otherIdentifier, null, null)).id();
        githubService.setRepositories(slug, otherProject, List.of(repositoryId));
        IssueResponse task = task("Solo de este proyecto");
        send("pull_request", pullEvent("opened", 80, "Privada", branch(task), "open", false, false));
        send("workflow_run", runEvent("completed", 9500, 78, "CI", branch(task), "push", "completed", "success"));

        // El otro proyecto ve el pull request y la ejecución (el repositorio también es suyo), sin la tarea
        assertThat(githubService.pullRequests(slug, otherProject, "open")).singleElement().satisfies(pull -> {
            assertThat(pull.number()).isEqualTo(80);
            assertThat(pull.issueId()).isNull();
            assertThat(pull.issueCode()).isNull();
            assertThat(pull.issueTitle()).isNull();
        });
        assertThat(githubService.runs(slug, otherProject)).singleElement().satisfies(run -> assertThat(run.issueCode()).isNull());
        assertThat(githubService.pullRequests(slug, projectId, "open")).singleElement()
                .satisfies(pull -> assertThat(pull.issueId()).isEqualTo(task.id()));
        // Y no puede quitárselo ni enlazarlo a una tarea suya
        UUID pullId = githubService.pullRequests(slug, otherProject, "open").getFirst().id();
        assertThatThrownBy(() -> githubService.linkPullRequest(slug, otherProject, pullId, null)).isInstanceOf(ConflictException.class);
        // Quien no configura GitHub no ve los demás repositorios del workspace ni los otros proyectos de cada uno
        ProjectStatus limited = githubService.project(slug, projectId, false);
        assertThat(limited.available()).isEmpty();
        assertThat(limited.repositories()).singleElement()
                .satisfies(repository -> assertThat(repository.projects()).extracting("projectId").containsExactly(projectId));
        assertThat(githubService.project(slug, projectId, true).repositories()).singleElement()
                .satisfies(repository -> assertThat(repository.projects()).hasSize(2));
        assertThat(githubService.workspace(slug, false).repositories()).isEmpty();
        assertThat(githubService.workspace(slug, false).connected()).isTrue();
    }

    @Test
    void alQuitarUnRepositorioDelProyectoSeVanSusReglas() {
        UUID repositoryId = connect();
        githubService.addRule(slug, projectId, pm.getId(), GithubService.FIRES_ON_COMPLETED, repositoryId, 88, "Notificar", "main");

        githubService.setRepositories(slug, projectId, List.of());
        issueService.move(slug, projectId, task("Ya sin repositorio").id(), pm.getId(), new MoveIssueRequest(done, null, null));

        assertThat(githubService.project(slug, projectId).rules()).isEmpty();
        assertThat(GITHUB.requests()).noneMatch(request -> request.endsWith("/dispatches"));
    }

    @Test
    void lasEjecucionesDeUnRepositorioSinProyectoNoSeGuardanNiSePreguntanSusChecks() {
        connect();
        githubService.setRepositories(slug, projectId, List.of());
        GITHUB.requests().clear();

        send("workflow_run", runEvent("completed", 9600, 78, "CI", "main", "push", "completed", "success"));
        send("check_suite", suiteEvent("completed"));

        assertThat(values("SELECT count(*)::text FROM github_workflow_runs WHERE run_id = 9600")).containsExactly("0");
        assertThat(GITHUB.requests()).noneMatch(request -> request.contains("/check-runs"));
    }

    @Test
    void elTokenRenovadoSeGuardaAunqueLaAccionFalle() {
        UUID repositoryId = connect();
        IssueResponse task = task("Con token vencido");
        sql("UPDATE github_accounts SET access_expires_at = now() - interval '1 minute' WHERE user_id = '" + pm.getId() + "'");
        GITHUB.fail("POST /repos/" + repositoryName + "/git/refs", 422);

        // La acción falla después de renovar (la rama ya existe): la renovación no se puede perder con ella
        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, pm.getId(), task.id(), repositoryId, "main", "repetida", true))
                .isInstanceOf(ConflictException.class);

        assertThat(values("SELECT (access_expires_at > now())::text FROM github_accounts WHERE user_id = '" + pm.getId() + "'")).containsExactly("true");
        assertThat(accounts.status(pm.getId()).linked()).isTrue();
        // Y la siguiente acción ya no necesita renovar otra vez
        int refreshes = GITHUB.bodies("POST /login/oauth/access_token").size();
        githubService.dispatch(slug, projectId, pm.getId(), repositoryId, 77, "Deploy", "main", Map.of(), null);
        assertThat(GITHUB.bodies("POST /login/oauth/access_token")).hasSize(refreshes);
    }

    @Test
    void unAvisoDemasiadoGrandeSeRechazaYUnoSinTamanoDeclaradoSeLee() {
        org.springframework.mock.web.MockHttpServletRequest huge = new org.springframework.mock.web.MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return 26L * 1024 * 1024;
            }
        };
        assertThat(webhookController.webhook(huge, "sha256=x", "push", "d1").getStatusCode().value()).isEqualTo(413);

        // Un aviso reenviado por partes (sin tamaño declarado) se lee y se atiende si la firma es buena
        connect();
        IssueResponse task = task("Por partes");
        byte[] body = json(branchEvent("create", branch(task)));
        org.springframework.mock.web.MockHttpServletRequest chunked = new org.springframework.mock.web.MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        chunked.setContent(body);
        assertThat(webhookController.webhook(chunked, signature(body), "create", "d-" + suffix).getStatusCode().value()).isEqualTo(204);
        assertThat(githubService.issue(slug, projectId, task.id()).branches()).hasSize(1);
    }

    // ----- La cuenta de GitHub de cada persona

    @Test
    void enlazarMiCuentaGuardaQuienSoyYLosTokensCifrados() {
        User luis = user("luis-" + suffix + "@test.com", "Luis Muñoz");
        assertThat(accounts.status(luis.getId()).linked()).isFalse();
        assertThat(accounts.status(luis.getId()).configured()).isTrue();

        String url = accounts.linkUrl(luis.getId());
        assertThat(url).startsWith(GITHUB.url() + "/login/oauth/authorize?client_id=Iv1.pruebas&redirect_uri=")
                .contains("http%3A%2F%2Flocalhost%3A5173%2Fgithub%2Fcallback");

        GithubAccounts.MyAccount account = accounts.complete(luis.getId(), "codigo-luis-" + suffix, state(url));

        assertThat(account.linked()).isTrue();
        assertThat(account.login()).isEqualTo("luis-" + suffix);
        assertThat(account.profileUrl()).isEqualTo(GITHUB.url() + "/luis-" + suffix);
        assertThat(account.avatarUrl()).isEqualTo("https://avatars.test/luis-" + suffix);
        // En la base no queda ningún token legible
        assertThat(values("SELECT access_token || ' ' || refresh_token FROM github_accounts WHERE user_id = '" + luis.getId() + "'"))
                .singleElement().asString().doesNotContain("acceso-").doesNotContain("refresco-");
    }

    @Test
    void elEnlaceParaConectarSoloLeSirveAQuienLoPidio() {
        User luis = user("luis-" + suffix + "@test.com", "Luis Muñoz");
        String state = state(accounts.linkUrl(pm.getId()));

        // Luis llega con el enlace que pidió la PM (se lo pasaron para que autorizara con su cuenta)
        assertThatThrownBy(() -> accounts.complete(luis.getId(), "codigo-luis-" + suffix, state)).isInstanceOf(BadRequestException.class);
        // Un estado sin firma o retocado, y un código que GitHub no acepta
        String own = state(accounts.linkUrl(luis.getId()));
        assertThatThrownBy(() -> accounts.complete(luis.getId(), "codigo-luis-" + suffix, "sin-firma")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> accounts.complete(luis.getId(), "codigo-luis-" + suffix, own.substring(0, own.length() - 2) + "00"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> accounts.complete(luis.getId(), "malo", own)).isInstanceOf(BadRequestException.class);
        // El enlace de instalar un workspace no sirve para enlazar una cuenta
        assertThatThrownBy(() -> accounts.complete(pm.getId(), "codigo-x", state(githubService.installUrl(slug, pm.getId()))))
                .isInstanceOf(BadRequestException.class);
        assertThat(accounts.status(luis.getId()).linked()).isFalse();
    }

    @Test
    void unaCuentaDeGitHubNoSeEnlazaADosPersonas() {
        User luis = user("luis-" + suffix + "@test.com", "Luis Muñoz");

        assertThatThrownBy(() -> accounts.complete(luis.getId(), "codigo-" + pmLogin, state(accounts.linkUrl(luis.getId()))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("otro usuario");
        // La misma persona sí puede volver a enlazar la suya
        assertThat(accounts.complete(pm.getId(), "codigo-" + pmLogin, state(accounts.linkUrl(pm.getId()))).linked()).isTrue();
    }

    @Test
    void sinCuentaEnlazadaSePuedeVerPeroNoActuar() {
        UUID repositoryId = connect();
        User luis = user("luis-" + suffix + "@test.com", "Luis Muñoz");
        IssueResponse task = task("Solo mirar");
        send("workflow_run", runEvent("completed", 9400, 78, "CI", "main", "push", "completed", "failure"));
        UUID runId = githubService.runs(slug, projectId).getFirst().id();

        assertThat(githubService.issue(slug, projectId, task.id()).available()).isTrue();
        assertThat(githubService.workflows(slug, projectId, repositoryId)).isNotEmpty();
        assertThatThrownBy(() -> githubService.dispatch(slug, projectId, luis.getId(), repositoryId, 77, "Deploy", "main", Map.of(), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Conecta tu cuenta de GitHub");
        assertThatThrownBy(() -> githubService.rerun(slug, projectId, luis.getId(), runId)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, luis.getId(), task.id(), repositoryId, "main", "rama-" + suffix, true))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Conecta tu cuenta de GitHub");
        assertThat(GITHUB.requests()).noneMatch(request -> request.endsWith("/dispatches") || request.endsWith("/git/refs") || request.endsWith("/rerun"));
        assertThat(githubService.runs(slug, projectId)).hasSize(1);
    }

    @Test
    void siMiCuentaNoTienePermisoEnElRepositorioSeExplica() {
        UUID repositoryId = connect();
        GITHUB.readOnly(pmLogin);
        IssueResponse task = task("Sin permiso");

        assertThatThrownBy(() -> githubService.dispatch(slug, projectId, pm.getId(), repositoryId, 77, "Deploy", "main", Map.of(), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("no tiene permiso de escritura en " + repositoryName);
        assertThatThrownBy(() -> githubService.createBranch(slug, projectId, pm.getId(), task.id(), repositoryId, "main", "rama-" + suffix, true))
                .isInstanceOf(ConflictException.class).hasMessageContaining(repositoryName);
        assertThat(githubService.runs(slug, projectId)).isEmpty();
        assertThat(githubService.issue(slug, projectId, task.id()).branches()).isEmpty();
    }

    @Test
    void elTokenSeRenuevaSoloYSiGitHubYaNoLoAceptaLaCuentaSeDesenlaza() {
        UUID repositoryId = connect();
        sql("UPDATE github_accounts SET access_expires_at = now() - interval '1 minute' WHERE user_id = '" + pm.getId() + "'");

        githubService.dispatch(slug, projectId, pm.getId(), repositoryId, 77, "Deploy", "main", Map.of(), null);

        assertThat(GITHUB.bodies("POST /login/oauth/access_token")).last()
                .satisfies(body -> assertThat(body.path("grant_type").asString()).isEqualTo("refresh_token"));
        assertThat(values("SELECT (access_expires_at > now())::text FROM github_accounts WHERE user_id = '" + pm.getId() + "'")).containsExactly("true");

        // La persona quitó la autorización en GitHub: al renovar ya no sirve, y la cuenta queda desenlazada
        GITHUB.revoked(pmLogin);
        sql("UPDATE github_accounts SET access_expires_at = now() - interval '1 minute' WHERE user_id = '" + pm.getId() + "'");
        assertThatThrownBy(() -> githubService.dispatch(slug, projectId, pm.getId(), repositoryId, 77, "Deploy", "main", Map.of(), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("caducó");
        assertThat(accounts.status(pm.getId()).linked()).isFalse();
    }

    @Test
    void desenlazarMiCuentaAnulaElTokenEnGitHub() {
        accounts.unlink(pm.getId());

        assertThat(accounts.status(pm.getId()).linked()).isFalse();
        assertThat(GITHUB.requests()).contains("DELETE /applications/Iv1.pruebas/token");
    }

    @Test
    void quienAbreElPullRequestEsLaPersonaDeTaskFlowSiEnlazoSuCuenta() {
        connect();
        IssueResponse task = task("Con autora");
        IssueResponse other = task("De alguien de fuera");

        send("pull_request", pullEvent("opened", 60, "Con autora", branch(task), "open", false, false, pmLogin));
        send("pull_request", pullEvent("opened", 61, "De fuera", branch(other), "open", false, false, "externo"));

        assertThat(githubService.issue(slug, projectId, task.id()).pullRequests()).singleElement().satisfies(pull -> {
            assertThat(pull.authorName()).isEqualTo("Paula PM");
            assertThat(pull.authorUserId()).isEqualTo(pm.getId());
        });
        assertThat(githubService.issue(slug, projectId, other.id()).pullRequests()).singleElement()
                .satisfies(pull -> assertThat(pull.authorName()).isNull());
        // En la actividad de la tarea, lo hizo ella; lo de alguien de fuera queda sin autor de TaskFlow
        assertThat(values("SELECT coalesce(a.actor_id::text, 'nadie') FROM issue_activities a WHERE a.issue_id = '" + task.id()
                + "' AND a.field = 'GITHUB' AND a.new_value LIKE 'PR_OPENED%'")).containsExactly(pm.getId().toString());
        assertThat(values("SELECT coalesce(a.actor_id::text, 'nadie') FROM issue_activities a WHERE a.issue_id = '" + other.id()
                + "' AND a.field = 'GITHUB' AND a.new_value LIKE 'PR_OPENED%'")).containsExactly("nadie");
        // El equipo ve quién tiene su cuenta enlazada
        assertThat(githubService.workspace(slug).accounts()).extracting("fullName", "login").containsExactly(
                org.assertj.core.groups.Tuple.tuple("Paula PM", pmLogin));
    }

    // ----- Ayudas

    /** Enlaza la cuenta de GitHub de una persona (como si hubiera autorizado en GitHub con ese usuario). */
    private void link(User user, String login) {
        assertThat(accounts.complete(user.getId(), "codigo-" + login, state(accounts.linkUrl(user.getId()))).linked()).isTrue();
    }

    /** Conecta el workspace y enlaza el repositorio al proyecto. */
    private UUID connect() {
        assertThat(githubService.completeSetup(installationId, state(githubService.installUrl(slug, pm.getId())))).endsWith("github=connected");
        UUID repositoryId = githubService.workspace(slug).repositories().getFirst().id();
        ProjectStatus status = githubService.setRepositories(slug, projectId, List.of(repositoryId));
        assertThat(status.repositories()).hasSize(1);
        return repositoryId;
    }

    private static String state(String url) {
        return url.substring(url.indexOf("state=") + "state=".length());
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
        return pullEvent(action, number, title, head, state, merged, draft, "luis");
    }

    private Map<String, Object> pullEvent(String action, int number, String title, String head, String state, boolean merged, boolean draft,
                                          String author) {
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
        pull.put("user", Map.of("login", author));
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
        /** Lo que se pidió con la cuenta de una persona: «POST /ruta como usuario». */
        private final List<String> asUser = new CopyOnWriteArrayList<>();
        /** Personas que no ven la instalación, que no pueden escribir en el repositorio, o que anularon la autorización. */
        private final java.util.Set<String> outsiders = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> readOnly = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> revoked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Map<String, Long> userIds = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicLong tokens = new AtomicLong();

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
            asUser.clear();
            outsiders.clear();
            readOnly.clear();
            revoked.clear();
        }

        List<String> asUser() {
            return asUser;
        }

        void outsider(String login) {
            outsiders.add(login);
        }

        void readOnly(String login) {
            readOnly.add(login);
        }

        void revoked(String login) {
            revoked.add(login);
        }

        /** Los tokens de una persona: «acceso-<usuario>-<n>» y «refresco-<usuario>». */
        private Map<String, Object> tokensOf(String login) {
            return Map.of("access_token", "acceso-" + login + "-" + tokens.incrementAndGet(), "expires_in", 28800,
                    "refresh_token", "refresco-" + login, "refresh_token_expires_in", 15897600, "token_type", "bearer");
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
            // La cuenta de una persona: su token es «acceso-<usuario>-<n>»
            String login = authorization != null && authorization.startsWith("Bearer acceso-")
                    ? authorization.substring("Bearer acceso-".length(), authorization.lastIndexOf('-')) : null;
            if (login != null) {
                asUser.add(request + " como " + login);
            }
            if (request.equals("POST /login/oauth/access_token")) {
                // Cambiar el código (o el token de refresco) por los tokens de la persona; GitHub responde 200 también al fallar
                JsonNode form = JSON.readTree(received);
                bodies.add(Map.entry(request, form));
                String code = form.path("code").asString("");
                String refresh = form.path("refresh_token").asString("");
                if (!"secreto-de-cliente".equals(form.path("client_secret").asString(""))) {
                    response = Map.of("error", "incorrect_client_credentials");
                } else if (code.startsWith("codigo-")) {
                    response = tokensOf(code.substring("codigo-".length()));
                } else if (refresh.startsWith("refresco-") && !revoked.contains(refresh.substring("refresco-".length()))) {
                    response = tokensOf(refresh.substring("refresco-".length()));
                } else {
                    response = Map.of("error", code.isEmpty() ? "bad_refresh_token" : "bad_verification_code");
                }
            } else if (path.startsWith("/applications/") && authorization != null && authorization.startsWith("Basic ")) {
                status = 204;
                response = null;
            } else if (authorization == null || !authorization.startsWith("Bearer ")) {
                status = 401;
                response = Map.of("message", "sin credenciales");
            } else if (login != null && request.equals("GET /user")) {
                response = Map.of("id", userIds.computeIfAbsent(login, key -> IDS.incrementAndGet()), "login", login,
                        "avatar_url", "https://avatars.test/" + login);
            } else if (login != null && request.equals("GET /user/installations")) {
                response = Map.of("total_count", 1, "installations", outsiders.contains(login) ? List.of() : List.of(Map.of("id", installationId)));
            } else if (login != null && readOnly.contains(login) && !exchange.getRequestMethod().equals("GET")) {
                status = 403;
                response = Map.of("message", "Resource not accessible by integration");
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
