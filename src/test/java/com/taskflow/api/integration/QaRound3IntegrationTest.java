package com.taskflow.api.integration;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.CloseCycleRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateStateRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.CycleMoveTarget;
import com.taskflow.api.dto.request.CycleRequest;
import com.taskflow.api.dto.request.InviteMemberRequest;
import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.RetroNoteRequest;
import com.taskflow.api.dto.request.SplitItemRequest;
import com.taskflow.api.dto.request.SplitStoryRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.dto.request.UpdateRetroNoteRequest;
import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.dto.response.CycleResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.ProjectResponse;
import com.taskflow.api.dto.response.RetroNoteResponse;
import com.taskflow.api.dto.response.RetroResponse;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.RetroColumn;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.CycleService;
import com.taskflow.api.service.InvitationService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.RetroService;
import com.taskflow.api.service.StateService;
import com.taskflow.api.service.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tercera ronda del QA (de la interfaz y de la API), contra un PostgreSQL de verdad (una base descartable,
 * {@link TestDatabase}).
 */
@SpringBootTest
class QaRound3IntegrationTest {

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
    private CycleService cycleService;
    @Autowired
    private RetroService retroService;
    @Autowired
    private InvitationService invitationService;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    private String suffix;
    private User pm;
    private User member;
    private String slug;
    private UUID projectId;
    private UUID todo;
    private UUID inProgress;
    private UUID done;

    @BeforeEach
    void project() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = user("pm-" + suffix + "@test.com", "Paula PM");
        member = user("luis-" + suffix + "@test.com", "Luis Miembro");
        slug = workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Equipo " + suffix, "r3-" + suffix))
                .slug();
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role) SELECT gen_random_uuid(), w.id, '"
                + member.getId() + "', 'MEMBER' FROM workspaces w WHERE slug = '" + slug + "'");
        ProjectResponse project = projectService.create(slug, pm.getId(),
                new CreateProjectRequest("Web", "R" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X"),
                        null, null));
        projectId = project.id();
        projectService.addMember(slug, projectId, new AddProjectMemberRequest(member.getId()));
        for (StateResponse state : stateService.getStates(slug, projectId)) {
            switch (state.group()) {
                case UNSTARTED -> todo = state.id();
                case STARTED -> inProgress = state.id();
                case COMPLETED -> done = state.id();
                default -> {
                }
            }
        }
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    // ----- La historia empieza aunque sus tareas no pasen por «en curso» -----

    /**
     * QA de la interfaz: se parte una historia en 2 tareas (nacen en Todo) y las dos se mueven directo a Done. Antes la
     * historia quedaba «lista para revisar» pero en Todo; ahora pasa a «en curso» con la primera, una sola vez.
     */
    @Test
    void unaHistoriaCuyasTareasPasanDirectoADoneEmpiezaYQuedaListaParaRevisar() {
        IssueResponse story = create("Perfil", IssueType.STORY, null, todo);
        List<IssueResponse> tasks = issueService.split(slug, projectId, story.id(), pm.getId(),
                new SplitStoryRequest(List.of(new SplitItemRequest("Formulario", null, null, null),
                        new SplitItemRequest("API", null, null, null)), null));
        assertThat(stateOf(story.id())).isEqualTo(todo.toString());

        issueService.move(slug, projectId, tasks.get(0).id(), member.getId(), new MoveIssueRequest(done, null, null, null));

        assertThat(stateOf(story.id())).isEqualTo(inProgress.toString());
        assertThat(value("SELECT old_value FROM issue_activities WHERE issue_id = '" + story.id()
                + "' AND field = 'STORY_AUTO_STATE' AND actor_id IS NULL"))
                .isEqualTo("Luis terminó " + tasks.get(0).code());

        issueService.move(slug, projectId, tasks.get(1).id(), member.getId(), new MoveIssueRequest(done, null, null, null));

        assertThat(autoStates(story.id())).isEqualTo("1");
        assertThat(value("SELECT review_notified FROM issues WHERE id = '" + story.id() + "'")).isEqualTo("t");
    }

    @Test
    void cambiarElEstadoDeTodoADoneConUnaEdicionTambienEmpiezaLaHistoria() {
        IssueResponse story = create("Perfil", IssueType.STORY, null, todo);
        IssueResponse task = create("Formulario", IssueType.TASK, story.id(), todo);

        issueService.update(slug, projectId, task.id(), member.getId(), new UpdateIssueRequest(null, null, done, null,
                null, null, null, null, null, null, null, null, null, null, null));

        assertThat(stateOf(story.id())).isEqualTo(inProgress.toString());
        assertThat(autoStates(story.id())).isEqualTo("1");
    }

    @Test
    void unaTareaQueLlegaYaTerminadaTambienEmpiezaLaHistoria() {
        IssueResponse story = create("Perfil", IssueType.STORY, null, todo);

        create("Formulario", IssueType.TASK, story.id(), done);

        assertThat(stateOf(story.id())).isEqualTo(inProgress.toString());
    }

    @Test
    void unEstadoSinEmpezarQuePasaACompletadoEmpiezaLasHistoriasDeSusTareas() {
        StateResponse qa = stateService.create(slug, projectId,
                new CreateStateRequest("QA " + suffix, "#123456", StateGroup.UNSTARTED, null));
        IssueResponse story = create("Perfil", IssueType.STORY, null, todo);
        create("Formulario", IssueType.TASK, story.id(), qa.id());

        stateService.update(slug, projectId, qa.id(), pm.getId(),
                new UpdateStateRequest(null, null, StateGroup.COMPLETED, null));

        assertThat(stateOf(story.id())).isEqualTo(inProgress.toString());
        assertThat(autoStates(story.id())).isEqualTo("1");
        assertThat(value("SELECT review_notified FROM issues WHERE id = '" + story.id() + "'")).isEqualTo("t");
    }

    @Test
    void unaTareaQueSeCancelaNoEmpiezaLaHistoria() {
        IssueResponse story = create("Perfil", IssueType.STORY, null, todo);
        IssueResponse task = create("Formulario", IssueType.TASK, story.id(), todo);
        UUID cancelled = stateService.getStates(slug, projectId).stream()
                .filter(state -> state.group() == StateGroup.CANCELLED).findFirst().orElseThrow().id();

        issueService.move(slug, projectId, task.id(), member.getId(), new MoveIssueRequest(cancelled, null, null, null));

        assertThat(stateOf(story.id())).isEqualTo(todo.toString());
    }

    // ----- Retrospectiva: el responsable de una acción ya convertida -----

    /** QA de la interfaz: se convierte la acción sin responsable y después se le pone uno; la tarea seguía sin asignar. */
    @Test
    void elResponsableQueLlegaDespuesDeConvertirLaAccionRecibeLaTarea() {
        UUID cycleId = retroCycle();
        RetroNoteResponse action = retroService.createNote(slug, projectId, cycleId, pm.getId(),
                new RetroNoteRequest(RetroColumn.ACTION, "Automatizar el deploy", null));
        IssueResponse task = retroService.convert(slug, projectId, cycleId, action.id(), pm.getId());
        assertThat(task.assignees()).isEmpty();

        retroService.updateNote(slug, projectId, cycleId, action.id(), pm.getId(),
                new UpdateRetroNoteRequest(null, member.getId(), null));

        assertThat(issueService.get(slug, projectId, task.id()).assignees())
                .extracting(UserSummaryResponse::id).containsExactly(member.getId());
        assertThat(value("SELECT count(*) FROM issue_activities WHERE issue_id = '" + task.id()
                + "' AND field = 'ASSIGNEES' AND actor_id = '" + pm.getId() + "'")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM notifications WHERE recipient_id = '" + member.getId()
                + "' AND issue_id = '" + task.id() + "'")).isEqualTo("1");
    }

    @Test
    void elResponsableNoPisaUnaTareaQueYaTieneAsignados() {
        UUID cycleId = retroCycle();
        RetroNoteResponse action = retroService.createNote(slug, projectId, cycleId, pm.getId(),
                new RetroNoteRequest(RetroColumn.ACTION, "Revisar alertas", null));
        IssueResponse task = retroService.convert(slug, projectId, cycleId, action.id(), pm.getId());
        issueService.update(slug, projectId, task.id(), pm.getId(), new UpdateIssueRequest(null, null, null, null,
                null, null, null, List.of(pm.getId()), null, null, null, null, null, null, null));

        retroService.updateNote(slug, projectId, cycleId, action.id(), pm.getId(),
                new UpdateRetroNoteRequest(null, member.getId(), null));

        assertThat(issueService.get(slug, projectId, task.id()).assignees())
                .extracting(UserSummaryResponse::id).containsExactly(pm.getId());
    }

    private UUID retroCycle() {
        CycleResponse cycle = cycleService.create(slug, projectId,
                new CycleRequest("Sprint " + suffix, null, LocalDate.now().minusDays(7), LocalDate.now().plusDays(7)));
        retroService.start(slug, projectId, cycle.id(), pm.getId());
        return cycle.id();
    }

    // ----- El avance de un ciclo cerrado es el de su retrospectiva -----

    /** QA de la interfaz: Sprint 1 se cerró con 1 de 2 y la lista mostraba 100 % y la retrospectiva 50 %. */
    @Test
    void unCicloCerradoMuestraEnLaListaElMismoAvanceQueSuRetrospectiva() {
        CycleResponse sprint = cycleService.create(slug, projectId,
                new CycleRequest("Sprint 1", null, LocalDate.now().minusDays(13), LocalDate.now().plusDays(1)));
        IssueResponse finished = create("Hecha", IssueType.TASK, null, done);
        IssueResponse pending = create("Pendiente", IssueType.TASK, null, todo);
        cycleService.addIssues(slug, projectId, sprint.id(), pm.getId(),
                new IssueIdsRequest(List.of(finished.id(), pending.id())));

        cycleService.close(slug, projectId, sprint.id(), pm.getId(),
                new CloseCycleRequest(CycleMoveTarget.BACKLOG, null, null, true));

        CycleResponse listed = cycleService.getCycles(slug, projectId).stream()
                .filter(cycle -> cycle.id().equals(sprint.id())).findFirst().orElseThrow();
        assertThat(listed.progress().completedIssues()).isEqualTo(1);
        assertThat(listed.progress().totalIssues()).isEqualTo(2);
        assertThat(listed.progress().percentage()).isEqualTo(50);
        assertThat(cycleService.get(slug, projectId, sprint.id()).progress()).isEqualTo(listed.progress());
        RetroResponse retro = retroService.getRetro(slug, projectId, sprint.id(), pm.getId());
        assertThat(retro.summary().completedCount()).isEqualTo(1);
        assertThat(retro.summary().totalCount()).isEqualTo(2);
    }

    // ----- Carreras del QA de la API -----

    /**
     * Dos creaciones de workspace a la vez con 9 propios: antes ambas contaban 9 y la persona quedaba con 11. Con la
     * fila del usuario bloqueada (por otra transacción), las dos esperan y, al soltarla, cuentan en fila.
     */
    @Test
    void dosWorkspacesALaVezNoPasanDelMaximo() throws Exception {
        for (int i = 2; i <= 9; i++) {
            workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Extra " + i, "r3-" + suffix + "-" + i));
        }
        assertThat(ownedBy(pm)).isEqualTo("9");
        List<Future<?>> calls = new ArrayList<>();
        try (Connection other = lockRow("users", pm.getId())) {
            for (String name : List.of("a", "b")) {
                calls.add(pool.submit(() -> workspaceService.create(pm.getId(),
                        new CreateWorkspaceRequest("Carrera " + name, "r3-" + suffix + "-" + name))));
            }
            awaitLockWaiters(2);
            other.commit();
        }

        assertThat(outcomes(calls)).containsExactlyInAnyOrder("ok", "ConflictException");
        assertThat(ownedBy(pm)).isEqualTo("10");
    }

    /**
     * Dos invitaciones a la vez al mismo correo: antes quedaban dos vigentes. Con la fila del workspace bloqueada
     * (por otra transacción), las dos esperan y, al soltarla, la segunda reemplaza a la primera.
     */
    @Test
    void dosInvitacionesALaVezAlMismoCorreoDejanUnaSola() throws Exception {
        String email = "nueva-" + suffix + "@test.com";
        UUID workspaceId = UUID.fromString(value("SELECT id FROM workspaces WHERE slug = '" + slug + "'"));
        List<Future<?>> calls = new ArrayList<>();
        try (Connection other = lockRow("workspaces", workspaceId)) {
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> invitationService.invite(slug, pm.getId(), new InviteMemberRequest(email))));
            }
            awaitLockWaiters(2);
            other.commit();
        }

        assertThat(outcomes(calls)).containsExactly("ok", "ok");
        assertThat(value("SELECT count(*) FROM workspace_invitations WHERE workspace_id = '" + workspaceId
                + "' AND email = '" + email + "' AND NOT accepted")).isEqualTo("1");
        assertThat(invitationService.getPending(slug)).hasSize(1);
    }

    // ----- Utilidades -----

    private IssueResponse create(String title, IssueType type, UUID parentId, UUID stateId) {
        return issueService.create(slug, projectId, pm.getId(), new CreateIssueRequest(title, null, stateId, null,
                parentId, null, null, null, null, null, null, type, null, null));
    }

    private String stateOf(UUID issueId) {
        return value("SELECT state_id FROM issues WHERE id = '" + issueId + "'");
    }

    private String autoStates(UUID storyId) {
        return value("SELECT count(*) FROM issue_activities WHERE issue_id = '" + storyId
                + "' AND field = 'STORY_AUTO_STATE'");
    }

    private String ownedBy(User user) {
        return value("SELECT count(*) FROM workspaces WHERE owner_id = '" + user.getId() + "'");
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

    private String value(String query) {
        try (Connection connection = TestDatabase.connect();
             PreparedStatement statement = connection.prepareStatement(query);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Una transacción que bloquea la fila hasta {@link Connection#commit()}. */
    private static Connection lockRow(String table, UUID id) throws SQLException {
        Connection connection = TestDatabase.connect();
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE id = '" + id + "' FOR UPDATE")) {
            lock.execute();
        }
        return connection;
    }

    /** Espera a que haya {@code count} conexiones de esta base esperando un bloqueo (como mucho 10 s). */
    private void awaitLockWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String waiting = value("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock'");
            if (Integer.parseInt(waiting) >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No hubo " + count + " peticiones esperando un bloqueo");
    }

    /** «ok» o el nombre de la excepción de cada llamada, en orden. */
    private static List<String> outcomes(List<Future<?>> calls) throws Exception {
        List<String> result = new ArrayList<>();
        for (Future<?> call : calls) {
            try {
                call.get(20, TimeUnit.SECONDS);
                result.add("ok");
            } catch (ExecutionException ex) {
                result.add(ex.getCause().getClass().getSimpleName());
            }
        }
        return result;
    }
}
