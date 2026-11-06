package com.taskflow.api.integration;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.ChangePasswordRequest;
import com.taskflow.api.dto.request.CommentRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.LoginRequest;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.ProjectResponse;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.UnauthorizedException;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.AuthService;
import com.taskflow.api.service.CommentService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Las carreras de la revisión contra un PostgreSQL de verdad (una base descartable, {@link TestDatabase}). Para que
 * sean deterministas, una conexión aparte bloquea la fila en disputa ({@code SELECT … FOR UPDATE}), se espera a que
 * las peticiones queden esperando ese bloqueo y entonces se suelta.
 */
@SpringBootTest
class ConcurrencyIntegrationTest {

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
    private CommentService commentService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private AuthService authService;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private User pm;
    private User member;
    private String slug;
    private UUID projectId;
    private UUID todo;
    private UUID inProgress;
    private UUID done;

    @BeforeEach
    void project() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
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

    private IssueResponse create(String title, IssueType type, UUID parentId, String description, UUID userId) {
        return issueService.create(slug, projectId, userId, new CreateIssueRequest(title, description, todo, null,
                parentId, null, null, null, null, null, null, type, null, null));
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

    /** Abre una transacción que bloquea la fila hasta {@link Connection#commit()}. */
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
    }

    private <T> Future<T> async(Callable<T> call) {
        return pool.submit(call);
    }

    // ----- Pruebas -----

    /**
     * Revisión del dominio #1: mover una tarjeta mientras otra persona guarda la descripción ya no devuelve la
     * descripción vieja (antes el UPDATE de mover escribía todas las columnas leídas al principio).
     */
    @Test
    void moverLaTarjetaNoPisaLaDescripcionQueOtraPersonaGuardoMientrasTanto() throws Exception {
        IssueResponse task = create("Login", IssueType.TASK, null, "Versión 1", pm.getId());

        Future<IssueResponse> move;
        try (Connection other = lockRow("issues", task.id())) {
            move = async(() -> issueService.move(slug, projectId, task.id(), member.getId(),
                    new MoveIssueRequest(inProgress, null, null)));
            awaitLockWaiters(1);
            try (PreparedStatement save = other.prepareStatement("UPDATE issues SET description = 'Versión 2', "
                    + "description_version = description_version + 1 WHERE id = '" + task.id() + "'")) {
                save.execute();
            }
            other.commit();
        }
        IssueResponse moved = move.get(20, TimeUnit.SECONDS);

        assertThat(value("SELECT description FROM issues WHERE id = '" + task.id() + "'")).isEqualTo("Versión 2");
        assertThat(value("SELECT description_version FROM issues WHERE id = '" + task.id() + "'"))
                .isEqualTo(String.valueOf(task.descriptionVersion() + 1));
        assertThat(value("SELECT state_id FROM issues WHERE id = '" + task.id() + "'"))
                .isEqualTo(inProgress.toString());
        assertThat(moved.description()).isEqualTo("Versión 2");
    }

    /**
     * Revisión del dominio #2: las dos últimas tareas de una historia terminan a la vez y el aviso «lista para revisar»
     * sale una vez (antes cada transacción veía la otra tarea abierta y no avisaba ninguna).
     */
    @Test
    void lasDosUltimasTareasTerminanALaVezYLaHistoriaAvisaUnaVez() throws Exception {
        IssueResponse story = create("Perfil", IssueType.STORY, null, null, pm.getId());
        IssueResponse first = create("Formulario", IssueType.TASK, story.id(), null, pm.getId());
        IssueResponse second = create("Endpoint", IssueType.TASK, story.id(), null, pm.getId());

        Future<IssueResponse> a;
        Future<IssueResponse> b;
        try (Connection other = lockRow("issues", story.id())) {
            a = async(() -> issueService.move(slug, projectId, first.id(), member.getId(),
                    new MoveIssueRequest(done, null, null)));
            b = async(() -> issueService.move(slug, projectId, second.id(), member.getId(),
                    new MoveIssueRequest(done, null, null)));
            awaitLockWaiters(2);
            other.commit();
        }
        a.get(20, TimeUnit.SECONDS);
        b.get(20, TimeUnit.SECONDS);

        assertThat(value("SELECT review_notified FROM issues WHERE id = '" + story.id() + "'")).isEqualTo("t");
        assertThat(value("SELECT count(*) FROM notifications WHERE type = 'STORY_READY' AND issue_id = '"
                + story.id() + "'")).isEqualTo("1");
    }

    /**
     * Revisión de seguridad #6, con el margen de reutilización: el mismo refresh token usado dos veces a la vez se rota
     * una sola vez (el bloqueo del usuario pone las peticiones en fila); la segunda llega dentro del margen, así que
     * rota el sucesor en vez de cerrar todas las sesiones. Las dos reciben un par y la cadena sigue con un token vivo.
     */
    @Test
    void elMismoRefreshTokenUsadoDosVecesALaVezDaDosParesSinCerrarLaSesion() throws Exception {
        AuthResponse session = login();
        UUID tokenId = UUID.fromString(value("SELECT id FROM refresh_tokens WHERE user_id = '" + member.getId()
                + "'"));

        Future<AuthResponse> a;
        Future<AuthResponse> b;
        try (Connection other = lockRow("refresh_tokens", tokenId)) {
            a = async(() -> authService.refresh(session.refreshToken()));
            b = async(() -> authService.refresh(session.refreshToken()));
            awaitLockWaiters(2);
            other.commit();
        }

        AuthResponse first = a.get(20, TimeUnit.SECONDS);
        AuthResponse second = b.get(20, TimeUnit.SECONDS);
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        assertThat(activeTokens()).isEqualTo("1");

        // Cualquiera de los dos sirve para seguir (el que quedó vivo, o el otro dentro del margen) y la sesión sigue
        AuthResponse fromFirst = authService.refresh(first.refreshToken());
        AuthResponse fromSecond = authService.refresh(second.refreshToken());
        assertThat(authService.refresh(fromSecond.refreshToken()).accessToken()).isNotBlank();
        assertThat(fromFirst.accessToken()).isNotBlank();
        assertThat(activeTokens()).isEqualTo("1");
    }

    /** El fallo original: dos recargas seguidas mandaban el token ya rotado y cerraban todas las sesiones. */
    @Test
    void dosRecargasSeguidasNoCierranLaSesion() {
        AuthResponse session = login();
        AuthResponse other = login(); // otra sesión del mismo usuario (otro equipo)

        authService.refresh(session.refreshToken()); // la respuesta se pierde al recargar
        AuthResponse reloaded = authService.refresh(session.refreshToken());
        AuthResponse thirdReload = authService.refresh(session.refreshToken());

        assertThat(authService.refresh(thirdReload.refreshToken()).accessToken()).isNotBlank();
        // La otra sesión no se tocó y esta sigue con un solo token vivo
        assertThat(authService.refresh(other.refreshToken()).accessToken()).isNotBlank();
        assertThat(activeTokens()).isEqualTo("2");
        assertThat(reloaded.refreshToken()).isNotEqualTo(thirdReload.refreshToken());
    }

    @Test
    void reutilizarloPasadoElMargenCierraTodasLasSesiones() {
        AuthResponse session = login();
        AuthResponse rotated = authService.refresh(session.refreshToken());
        sql("UPDATE refresh_tokens SET revoked_at = now() - interval '1 minute' WHERE user_id = '" + member.getId()
                + "' AND revoked");

        assertThatThrownBy(() -> authService.refresh(session.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(activeTokens()).isEqualTo("0");
        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void trasElLogoutElTokenViejoNoTieneMargen() {
        AuthResponse session = login();
        AuthResponse rotated = authService.refresh(session.refreshToken());
        authService.logout(rotated.refreshToken());

        assertThatThrownBy(() -> authService.refresh(session.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(activeTokens()).isEqualTo("0");
    }

    @Test
    void logoutConElTokenViejoCierraTambienElSucesorVivo() {
        // Dos recargas: la respuesta del refresh se perdió y el navegador se quedó con el token viejo; cerrar sesión
        // con él dejaba vivo el sucesor (servía una semana más)
        AuthResponse session = login();
        AuthResponse other = login();
        AuthResponse rotated = authService.refresh(session.refreshToken());
        authService.logout(session.refreshToken());

        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
        // Solo esa sesión: la del otro equipo sigue
        assertThat(authService.refresh(other.refreshToken()).accessToken()).isNotBlank();
        assertThat(activeTokens()).isEqualTo("1");
    }

    @Test
    void reusarElTokenAnteriorTrasElLogoutNoCierraLasOtrasSesiones() {
        // Otra pestaña con el token anterior (rotado hace segundos) pide un refresh después del logout: es un 401, no
        // un robo que cierre también la sesión del otro equipo
        AuthResponse session = login();
        AuthResponse other = login();
        AuthResponse rotated = authService.refresh(session.refreshToken());
        authService.logout(rotated.refreshToken());

        assertThatThrownBy(() -> authService.refresh(session.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(authService.refresh(other.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void trasCambiarLaContrasenaElTokenViejoNoTieneMargen() {
        AuthResponse session = login();
        AuthResponse rotated = authService.refresh(session.refreshToken());
        AuthResponse changed = authService.changePassword(member.getId(),
                new ChangePasswordRequest("Clave1234", "ClaveNueva5678"));

        assertThatThrownBy(() -> authService.refresh(session.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
        // Los tokens viejos ya no existen (no es una reutilización): la sesión del cambio sigue
        assertThat(authService.refresh(changed.refreshToken()).accessToken()).isNotBlank();
    }

    private AuthResponse login() {
        return authService.login(new LoginRequest(member.getEmail(), "Clave1234"), new ClientInfo("127.0.0.1", "JUnit"));
    }

    private String activeTokens() {
        return value("SELECT count(*) FROM refresh_tokens WHERE user_id = '" + member.getId() + "' AND NOT revoked");
    }

    /**
     * Revisión de seguridad #2: quien sale del proyecto deja de estar asignado y deja de ver (y contar) sus avisos.
     */
    @Test
    void quienSaleDelProyectoDejaDeEstarAsignadoYDeVerSusAvisos() {
        IssueResponse task = issueService.create(slug, projectId, pm.getId(), new CreateIssueRequest("Pagos", null,
                todo, null, null, null, null, List.of(member.getId()), null, null, null, IssueType.TASK, null, null));
        commentService.create(slug, projectId, task.id(), pm.getId(), new CommentRequest("¿Cómo vas?"));
        assertThat(notificationService.countUnread(member.getId()).count()).isEqualTo(2);

        projectService.removeMember(slug, projectId, member.getId());

        assertThat(value("SELECT count(*) FROM issue_assignees WHERE user_id = '" + member.getId() + "'"))
                .isEqualTo("0");
        assertThat(notificationService.countUnread(member.getId()).count()).isZero();
        assertThat(notificationService.getMyNotifications(member.getId(), false, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))).content()).isEmpty();
        // Un comentario nuevo ya no le llega aunque haya creado la tarea o siga en el historial
        commentService.create(slug, projectId, task.id(), pm.getId(), new CommentRequest("Otro"));
        assertThat(value("SELECT count(*) FROM notifications WHERE recipient_id = '" + member.getId() + "'"))
                .isEqualTo("2");
    }

    /** Revisión de seguridad #7: los contadores del proyecto salen de una consulta, con «vencidas» en app.timezone. */
    @Test
    void losContadoresDelProyectoSalenDeLaConsultaAgregada() {
        create("Abierta", IssueType.TASK, null, null, pm.getId());
        IssueResponse late = create("Vencida", IssueType.BUG, null, null, pm.getId());
        sql("UPDATE issues SET due_date = '" + LocalDate.now().minusDays(3) + "' WHERE id = '" + late.id() + "'");
        IssueResponse story = create("Historia", IssueType.STORY, null, null, pm.getId());
        create("Sin partir", IssueType.STORY, null, null, pm.getId());
        create("Hija", IssueType.TASK, story.id(), null, pm.getId());

        ProjectResponse project = projectService.get(slug, projectId);

        assertThat(project.issueCount()).isEqualTo(3);
        assertThat(project.openIssueCount()).isEqualTo(3);
        assertThat(project.overdueIssueCount()).isEqualTo(1);
        assertThat(project.openStoryCount()).isEqualTo(2);
        assertThat(project.unsplitStoryCount()).isEqualTo(1);
    }

    /**
     * Revisión de seguridad #8 / dominio #7: cambiar el grupo de un estado cierra (con fecha y autor) las historias que
     * están en él y pone al día las historias de sus tareas.
     */
    @Test
    void cambiarElGrupoDeUnEstadoCierraSusHistoriasYAvisaLasListas() {
        IssueResponse story = create("Perfil", IssueType.STORY, null, null, pm.getId());
        IssueResponse task = create("Formulario", IssueType.TASK, story.id(), null, pm.getId());
        IssueResponse otherStory = create("Pagos", IssueType.STORY, null, null, pm.getId());
        issueService.update(slug, projectId, otherStory.id(), pm.getId(), new UpdateIssueRequest(null, null,
                inProgress, null, null, null, null, null, null, null, null, null, null, null, null));
        issueService.move(slug, projectId, task.id(), member.getId(), new MoveIssueRequest(inProgress, null, null));

        // «In Progress» pasa a ser un estado completado: la tarea queda hecha y su historia lista para revisar…
        stateService.update(slug, projectId, inProgress, pm.getId(),
                new UpdateStateRequest(null, null, StateGroup.COMPLETED, null));

        assertThat(value("SELECT closed_by_id FROM issues WHERE id = '" + otherStory.id() + "'"))
                .isEqualTo(pm.getId().toString());
        assertThat(value("SELECT closed_at IS NOT NULL FROM issues WHERE id = '" + otherStory.id() + "'"))
                .isEqualTo("t");

        // …y al volver a abrirlo se reabren
        stateService.update(slug, projectId, inProgress, pm.getId(),
                new UpdateStateRequest(null, null, StateGroup.STARTED, null));
        assertThat(value("SELECT closed_at FROM issues WHERE id = '" + otherStory.id() + "'")).isNull();
    }
}
