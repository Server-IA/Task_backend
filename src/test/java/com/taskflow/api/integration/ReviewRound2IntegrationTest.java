package com.taskflow.api.integration;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.ChangePasswordRequest;
import com.taskflow.api.dto.request.CreateIssueRelationRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateStateRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.IssueRelationKind;
import com.taskflow.api.dto.request.ActivityFeedType;
import com.taskflow.api.dto.request.LoginRequest;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.ReorderStatesRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.dto.response.ActivityFeedItemResponse;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.NotificationResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.dto.response.ProjectResponse;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.NotificationType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.UnauthorizedException;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.security.CredentialsGuard;
import com.taskflow.api.security.JwtService;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.AuthService;
import com.taskflow.api.service.IssueRelationService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.StateService;
import com.taskflow.api.service.WorkspaceService;
import com.taskflow.api.util.ClientInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * La segunda revisión del backend y las funciones nuevas contra un PostgreSQL de verdad (una base descartable,
 * {@link TestDatabase}): las carreras con bloqueos, el cursor de la bandeja y del feed, el resumen de miembros, el
 * orden de los estados y el cambio de grupo en bloque.
 */
@SpringBootTest
class ReviewRound2IntegrationTest {

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
    private IssueRelationService relationService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private AuthService authService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private CredentialsGuard credentialsGuard;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

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
        slug = workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Equipo " + suffix, "eq-" + suffix))
                .slug();
        workspaceMember(member);
        ProjectResponse project = projectService.create(slug, pm.getId(),
                new CreateProjectRequest("Web", "W" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X"),
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

    private User user(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode("Clave1234"));
        user.setEmailVerified(true);
        return userRepository.saveAndFlush(user);
    }

    private void workspaceMember(User user) {
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role) "
                + "SELECT gen_random_uuid(), w.id, '" + user.getId() + "', 'MEMBER' FROM workspaces w WHERE slug = '"
                + slug + "'");
    }

    private IssueResponse create(String title, IssueType type, UUID parentId, UUID stateId, List<UUID> assignees) {
        return issueService.create(slug, projectId, pm.getId(), new CreateIssueRequest(title, null, stateId, null,
                parentId, null, null, assignees, null, null, null, type, null, null));
    }

    private IssueResponse create(String title, IssueType type, UUID parentId) {
        return create(title, type, parentId, todo, null);
    }

    private static UpdateIssueRequest parent(UUID parentId) {
        return new UpdateIssueRequest(null, null, null, null, parentId, null, null, null, null, null, null, null, null,
                null, null);
    }

    // ----- SQL directo -----

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

    /** Abre una transacción que bloquea las filas hasta {@link Connection#commit()}. */
    private static Connection lockRows(String table, UUID... ids) throws SQLException {
        Connection connection = TestDatabase.connect();
        connection.setAutoCommit(false);
        for (UUID id : ids) {
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT 1 FROM " + table + " WHERE id = '" + id + "' FOR UPDATE")) {
                lock.execute();
            }
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

    private <T> Future<T> async(Callable<T> call) {
        return pool.submit(call);
    }

    /** Recorre un listado por cursor hasta el final y devuelve los ids en orden. */
    private static <T> List<UUID> walk(Function<String, PageResponse<T>> page, Function<T, UUID> id) {
        List<UUID> ids = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 100; i++) {
            PageResponse<T> current = page.apply(cursor);
            current.content().forEach(item -> ids.add(id.apply(item)));
            cursor = current.nextCursor();
            if (cursor == null) {
                return ids;
            }
        }
        throw new AssertionError("El cursor no terminó");
    }

    // ----- #3: credenciales -----

    /**
     * Revisión 2 #3a: un refresh que llega mientras se cambia la contraseña espera el bloqueo de la fila del usuario
     * y, cuando el cambio termina, su token ya no existe: no se crea una sesión nueva que el cambio no vea.
     */
    @Test
    void unRefreshQueEsperaUnCambioDeContrasenaNoCreaUnaSesionNueva() throws Exception {
        AuthResponse session = authService.login(new LoginRequest(member.getEmail(), "Clave1234"),
                new ClientInfo("127.0.0.1", "JUnit"));

        Future<AuthResponse> refresh;
        try (Connection change = lockRows("users", member.getId())) {
            refresh = async(() -> authService.refresh(session.refreshToken()));
            awaitLockWaiters(1);
            // Lo mismo que hace el cambio de contraseña con la fila bloqueada
            try (PreparedStatement delete = change.prepareStatement("DELETE FROM refresh_tokens WHERE user_id = '"
                    + member.getId() + "'");
                 PreparedStatement bump = change.prepareStatement("UPDATE users SET credentials_version = "
                         + "credentials_version + 1 WHERE id = '" + member.getId() + "'")) {
                delete.execute();
                bump.execute();
            }
            change.commit();
        }

        assertThatThrownBy(() -> refresh.get(20, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(UnauthorizedException.class);
        assertThat(value("SELECT count(*) FROM refresh_tokens WHERE user_id = '" + member.getId() + "'"))
                .isEqualTo("0");
    }

    /**
     * Revisión 2 #3b: tras cambiar la contraseña, el access token anterior deja de valer aunque se haya emitido en el
     * mismo segundo; el de la sesión que la cambió sí vale.
     */
    @Test
    void elAccessTokenAnteriorNoValeAunqueSeaDelMismoSegundo() {
        AuthResponse before = authService.login(new LoginRequest(member.getEmail(), "Clave1234"),
                new ClientInfo("127.0.0.1", "JUnit"));
        AuthResponse after = authService.changePassword(member.getId(),
                new ChangePasswordRequest("Clave1234", "Nueva12345"));

        int oldVersion = jwtService.parse(before.accessToken()).orElseThrow().credentialsVersion();
        int newVersion = jwtService.parse(after.accessToken()).orElseThrow().credentialsVersion();
        assertThat(newVersion).isEqualTo(oldVersion + 1);
        assertThat(credentialsGuard.accepts(member.getId(), oldVersion)).isFalse();
        assertThat(credentialsGuard.accepts(member.getId(), newVersion)).isTrue();
        assertThat(value("SELECT credentials_version FROM users WHERE id = '" + member.getId() + "'"))
                .isEqualTo(String.valueOf(newVersion));
    }

    // ----- #4 y #5: bloqueos de las historias -----

    /**
     * Revisión 2 #4: dos tareas que se cruzan de historia a la vez (A de P1 a P2 y B de P2 a P1) terminan las dos, sin
     * un deadlock (antes, una respondía 409 tras el deadlock_timeout).
     */
    @Test
    void dosTareasQueSeCruzanDeHistoriaNoSeBloqueanMutuamente() throws Exception {
        IssueResponse p1 = create("Perfil", IssueType.STORY, null);
        IssueResponse p2 = create("Pagos", IssueType.STORY, null);
        IssueResponse a = create("Formulario", IssueType.TASK, p1.id());
        IssueResponse b = create("Pasarela", IssueType.TASK, p2.id());

        Future<IssueResponse> moveA;
        Future<IssueResponse> moveB;
        try (Connection other = lockRows("issues", p1.id(), p2.id())) {
            moveA = async(() -> issueService.update(slug, projectId, a.id(), member.getId(), parent(p2.id())));
            moveB = async(() -> issueService.update(slug, projectId, b.id(), member.getId(), parent(p1.id())));
            awaitLockWaiters(2);
            other.commit();
        }

        assertThat(moveA.get(20, TimeUnit.SECONDS).parent().id()).isEqualTo(p2.id());
        assertThat(moveB.get(20, TimeUnit.SECONDS).parent().id()).isEqualTo(p1.id());
    }

    /**
     * Revisión 2 #5: el /reopen del project manager y mover una tarea con reopenParent a la vez reabren la historia una
     * sola vez (antes las dos la veían cerrada y quedaban dos «reabrió» en su historial).
     */
    @Test
    void reabrirLaHistoriaDosVecesALaVezLaReabreUnaSolaVez() throws Exception {
        IssueResponse story = create("Perfil", IssueType.STORY, null);
        IssueResponse task = create("Formulario", IssueType.TASK, story.id());
        issueService.move(slug, projectId, task.id(), pm.getId(), new MoveIssueRequest(done, null, null));
        issueService.closeStory(slug, projectId, story.id(), pm.getId());
        String reopenedBefore = reopenings(story.id());

        Future<IssueResponse> reopen;
        Future<IssueResponse> move;
        try (Connection other = lockRows("issues", story.id())) {
            reopen = async(() -> issueService.reopenStory(slug, projectId, story.id(), pm.getId()));
            move = async(() -> issueService.move(slug, projectId, task.id(), pm.getId(),
                    new MoveIssueRequest(todo, null, null, true)));
            awaitLockWaiters(2);
            other.commit();
        }

        move.get(20, TimeUnit.SECONDS);
        try {
            reopen.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            // Si la tarea llegó primero, la historia ya estaba abierta
            assertThat(ex.getCause()).isInstanceOf(ConflictException.class);
        }
        assertThat(Integer.parseInt(reopenings(story.id()))).isEqualTo(Integer.parseInt(reopenedBefore) + 1);
    }

    /** Entradas del historial de la historia que la sacan de «Done». */
    private String reopenings(UUID storyId) {
        return value("SELECT count(*) FROM issue_activities WHERE issue_id = '" + storyId
                + "' AND field = 'STATE' AND old_value = 'Done'");
    }

    // ----- #7: cambio de grupo de un estado -----

    @Test
    void unEstadoQuePasaAEnCursoEmpiezaLasHistoriasDeSusTareas() {
        StateResponse qa = stateService.create(slug, projectId,
                new CreateStateRequest("QA " + suffix, "#123456", StateGroup.UNSTARTED, null));
        IssueResponse story = create("Perfil", IssueType.STORY, null);
        create("Formulario", IssueType.TASK, story.id(), qa.id(), null);

        stateService.update(slug, projectId, qa.id(), pm.getId(),
                new UpdateStateRequest(null, null, StateGroup.STARTED, null));

        assertThat(value("SELECT state_id FROM issues WHERE id = '" + story.id() + "'")).isEqualTo(inProgress.toString());
        assertThat(value("SELECT count(*) FROM issue_activities WHERE issue_id = '" + story.id()
                + "' AND field = 'STORY_AUTO_STATE'")).isEqualTo("1");
    }

    @Test
    void unaTareaBloqueadaPorDosDelEstadoRecibeUnSoloAviso() {
        StateResponse review = stateService.create(slug, projectId,
                new CreateStateRequest("Review " + suffix, "#654321", StateGroup.STARTED, null));
        IssueResponse first = create("API", IssueType.TASK, null, review.id(), null);
        IssueResponse second = create("Base de datos", IssueType.TASK, null, review.id(), null);
        IssueResponse blocked = create("Pantalla", IssueType.TASK, null, todo, List.of(member.getId()));
        relationService.create(slug, projectId, first.id(), pm.getId(),
                new CreateIssueRelationRequest(IssueRelationKind.BLOCKS, blocked.id()));
        relationService.create(slug, projectId, second.id(), pm.getId(),
                new CreateIssueRelationRequest(IssueRelationKind.BLOCKS, blocked.id()));

        stateService.update(slug, projectId, review.id(), pm.getId(),
                new UpdateStateRequest(null, null, StateGroup.COMPLETED, null));

        assertThat(value("SELECT count(*) FROM notifications WHERE type = 'ISSUE_UNBLOCKED' AND recipient_id = '"
                + member.getId() + "' AND issue_id = '" + blocked.id() + "'")).isEqualTo("1");
    }

    // ----- Parte B -----

    @Test
    void laBandejaSeRecorrePorCursorSinRepetirNiSaltarseNinguna() {
        for (int i = 0; i < 7; i++) {
            create("Asignada " + i, IssueType.TASK, null, todo, List.of(member.getId()));
        }
        // Varias con la misma fecha: el id desempata
        sql("UPDATE notifications SET created_at = '2026-10-29T12:00:00.123456Z' WHERE recipient_id = '"
                + member.getId() + "' AND id IN (SELECT id FROM notifications WHERE recipient_id = '" + member.getId()
                + "' ORDER BY id LIMIT 4)");

        List<UUID> all = notificationService.getMyNotifications(member.getId(), false, null,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"))).content().stream()
                .map(NotificationResponse::id).toList();
        List<UUID> walked = walk(cursor -> notificationService.getMyNotifications(member.getId(), false,
                List.of(NotificationType.ISSUE_ASSIGNED),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")), cursor), NotificationResponse::id);

        assertThat(all).hasSize(7);
        assertThat(walked).containsExactlyElementsOf(all);
        assertThat(new HashSet<>(walked)).hasSize(7);
        // Con otro tipo, nada
        assertThat(notificationService.getMyNotifications(member.getId(), false,
                List.of(NotificationType.ISSUE_MENTIONED), PageRequest.of(0, 2), null).content()).isEmpty();
    }

    @Test
    void elFeedSeRecorrePorCursorSinRepetirNiSaltarseNinguna() {
        for (int i = 0; i < 5; i++) {
            create("Tarea " + i, IssueType.TASK, null);
        }
        sql("UPDATE issue_activities SET created_at = '2026-10-29T12:00:00Z' WHERE issue_id IN "
                + "(SELECT id FROM issues WHERE project_id = '" + projectId + "')");

        List<UUID> all = activityService.getWorkspaceFeed(slug, pm.getId(), ActivityFeedType.ALL, projectId, null,
                0, 100).content().stream().map(ActivityFeedItemResponse::id).toList();
        List<UUID> walked = walk(cursor -> activityService.getWorkspaceFeed(slug, pm.getId(), ActivityFeedType.ALL,
                projectId, null, 0, 2, cursor), ActivityFeedItemResponse::id);

        assertThat(all).hasSize(5);
        assertThat(walked).containsExactlyElementsOf(all);
    }

    @Test
    void cadaProyectoTraeSuResumenDeMiembros() {
        List<String> names = new ArrayList<>();
        for (String name : List.of("Zoe", "Bruno", "Carla", "Diego", "Elena", "Andrés")) {
            User extra = user(name.toLowerCase() + "-" + suffix + "@test.com", name);
            workspaceMember(extra);
            projectService.addMember(slug, projectId, new AddProjectMemberRequest(extra.getId()));
            names.add(name);
        }
        ProjectResponse empty = projectService.create(slug, pm.getId(), new CreateProjectRequest("Vacío",
                "V" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "Y"), null, null));
        sql("DELETE FROM project_members WHERE project_id = '" + empty.id() + "'");

        List<ProjectResponse> projects = projectService.getProjects(slug, pm.getId());

        ProjectResponse web = projects.stream().filter(project -> project.id().equals(projectId)).findFirst()
                .orElseThrow();
        // Paula, Luis y los seis nuevos; los cinco primeros por nombre
        assertThat(web.memberCount()).isEqualTo(8);
        assertThat(web.members()).extracting(UserSummaryResponse::fullName)
                .containsExactly("Andrés", "Bruno", "Carla", "Diego", "Elena");
        ProjectResponse vacio = projects.stream().filter(project -> project.id().equals(empty.id())).findFirst()
                .orElseThrow();
        assertThat(vacio.memberCount()).isZero();
        assertThat(vacio.members()).isEmpty();
    }

    @Test
    void reordenarLosEstadosEnUnaTransaccion() {
        List<UUID> current = stateService.getStates(slug, projectId).stream().map(StateResponse::id).toList();
        List<UUID> reversed = new ArrayList<>(current);
        java.util.Collections.reverse(reversed);

        List<StateResponse> result = stateService.reorder(slug, projectId, new ReorderStatesRequest(reversed));

        assertThat(result).extracting(StateResponse::id).containsExactlyElementsOf(reversed);
        assertThat(stateService.getStates(slug, projectId)).extracting(StateResponse::id)
                .containsExactlyElementsOf(reversed);
        assertThat(result).extracting(StateResponse::position).containsExactly(0, 1, 2, 3, 4);

        // Si falta uno, no cambia nada
        assertThatThrownBy(() -> stateService.reorder(slug, projectId,
                new ReorderStatesRequest(current.subList(0, 4))))
                .isInstanceOf(BadRequestException.class);
        assertThat(stateService.getStates(slug, projectId)).extracting(StateResponse::id)
                .containsExactlyElementsOf(reversed);
        Set<String> distinct = new HashSet<>();
        for (UUID id : reversed) {
            distinct.add(value("SELECT position FROM states WHERE id = '" + id + "'"));
        }
        assertThat(distinct).hasSize(5);
    }
}
