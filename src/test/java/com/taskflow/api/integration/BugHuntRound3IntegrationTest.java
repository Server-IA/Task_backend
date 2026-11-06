package com.taskflow.api.integration;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateStateRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.LoginRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.UnauthorizedException;
import com.taskflow.api.maintenance.MaintenanceJobs;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.AuthService;
import com.taskflow.api.service.InvitationService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.StateService;
import com.taskflow.api.service.WorkspaceService;
import com.taskflow.api.util.ClientInfo;
import com.taskflow.api.util.TokenUtils;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Bug hunt 3 (backend) contra un PostgreSQL de verdad ({@link TestDatabase}): borrar un estado mientras alguien pone
 * una tarea en él, aceptar dos veces una invitación, la limpieza de invitaciones vencidas y el logout de una sesión que
 * rotó varias veces. Como en
 * {@link ConcurrencyIntegrationTest}, una conexión aparte hace de «la otra petición» y se espera a que la del servicio
 * quede bloqueada antes de soltarla.
 */
@SpringBootTest
class BugHuntRound3IntegrationTest {

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
    private InvitationService invitationService;
    @Autowired
    private MaintenanceJobs maintenanceJobs;
    @Autowired
    private AuthService authService;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private User pm;
    private String slug;
    private UUID workspaceId;
    private UUID projectId;

    @BeforeEach
    void project() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = user("pm-" + suffix + "@test.com", "Paula PM");
        WorkspaceResponse workspace = workspaceService.create(pm.getId(),
                new CreateWorkspaceRequest("Equipo " + suffix, "eq-" + suffix));
        slug = workspace.slug();
        workspaceId = workspace.id();
        projectId = projectService.create(slug, pm.getId(), new CreateProjectRequest("Web",
                "W" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X"), null, null)).id();
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private User user(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode("Clave1234"));
        user.setEmailVerified(true);
        return userRepository.saveAndFlush(user);
    }

    private StateResponse newState(String name) {
        return stateService.create(slug, projectId, new CreateStateRequest(name, "#112233", StateGroup.STARTED, null));
    }

    private IssueResponse createIn(UUID stateId) {
        return issueService.create(slug, projectId, pm.getId(), new CreateIssueRequest("Login", null, stateId, null,
                null, null, null, null, null, null, null, null, null, null));
    }

    /** Una invitación vigente (o no) con un token conocido; devuelve el token en claro. */
    private String invitation(String email, String expiresIn, boolean accepted) {
        String token = "tok-" + UUID.randomUUID();
        sql("INSERT INTO workspace_invitations (id, workspace_id, email, role, token_hash, invited_by_id, expires_at, "
                + "accepted) VALUES (gen_random_uuid(), '" + workspaceId + "', '" + email + "', 'MEMBER', '"
                + TokenUtils.sha256(token) + "', '" + pm.getId() + "', now() + interval '" + expiresIn + "', "
                + accepted + ")");
        return token;
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

    /** Abre una transacción que bloquea la fila ({@code FOR UPDATE} o {@code FOR KEY SHARE}) hasta su commit. */
    private static Connection lockRow(String table, UUID id, String mode) throws SQLException {
        Connection connection = TestDatabase.connect();
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE id = '" + id + "' " + mode)) {
            lock.execute();
        }
        return connection;
    }

    private static void execute(Connection connection, String statement) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(statement)) {
            query.execute();
        }
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

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
    }

    // ----- Estados -----

    /**
     * Se borra el estado mientras alguien crea una tarea en él: la creación espera al borrado y responde que el estado ya
     * no existe. Antes no esperaba y fallaba al insertar, con el 409 genérico de la clave foránea («La operación entra en
     * conflicto con datos existentes»).
     */
    @Test
    void crearUnaTareaEnUnEstadoQueSeEstaBorrandoRespondeQueElEstadoNoExiste() throws Exception {
        StateResponse review = newState("Revisión");

        Future<IssueResponse> create;
        try (Connection deleting = lockRow("states", review.id(), "FOR UPDATE")) {
            create = async(() -> createIn(review.id()));
            awaitLockWaiters(1);
            execute(deleting, "DELETE FROM states WHERE id = '" + review.id() + "'");
            deleting.commit();
        }

        assertThat(failureOf(create)).isInstanceOf(BadRequestException.class)
                .hasMessage("El estado no pertenece al proyecto");
        assertThat(value("SELECT count(*) FROM issues WHERE project_id = '" + projectId + "'")).isEqualTo("0");
    }

    /**
     * Alguien pone una tarea en el estado mientras el project manager lo borra: el borrado espera y responde que el estado
     * tiene tareas. Antes no veía la tarea, y el DELETE fallaba con el 409 genérico de la clave foránea.
     */
    @Test
    void borrarUnEstadoMientrasAlguienLePoneUnaTareaRespondeQueTieneTareas() throws Exception {
        StateResponse review = newState("Revisión");
        StateResponse other = newState("Otra");
        IssueResponse issue = createIn(other.id());

        Future<Void> delete;
        try (Connection moving = lockRow("states", review.id(), "FOR KEY SHARE")) {
            delete = async(() -> {
                stateService.delete(slug, projectId, review.id());
                return null;
            });
            awaitLockWaiters(1);
            execute(moving, "UPDATE issues SET state_id = '" + review.id() + "' WHERE id = '" + issue.id() + "'");
            moving.commit();
        }

        assertThat(failureOf(delete)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("El estado tiene tareas");
        assertThat(value("SELECT count(*) FROM states WHERE id = '" + review.id() + "'")).isEqualTo("1");
    }

    // ----- Invitaciones -----

    /**
     * Aceptar dos veces la misma invitación (doble clic, dos pestañas) deja a la persona dentro una vez y las dos
     * respuestas son el workspace. Antes la segunda respondía 409 (a la vez) o 400 «no es válida o ya expiró» (después).
     */
    @Test
    void aceptarDosVecesALaVezLaMismaInvitacionEntraUnaVezYRespondeDosVecesElWorkspace() throws Exception {
        User guest = user("invitada-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com", "Inés Invitada");
        String token = invitation(guest.getEmail(), "7 days", false);
        UUID invitationId = UUID.fromString(value("SELECT id FROM workspace_invitations WHERE token_hash = '"
                + TokenUtils.sha256(token) + "'"));

        Future<WorkspaceResponse> first;
        Future<WorkspaceResponse> second;
        try (Connection other = lockRow("workspace_invitations", invitationId, "FOR UPDATE")) {
            first = async(() -> invitationService.accept(token, guest.getId()));
            second = async(() -> invitationService.accept(token, guest.getId()));
            awaitLockWaiters(2);
            other.commit();
        }

        assertThat(first.get(20, TimeUnit.SECONDS).myRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(second.get(20, TimeUnit.SECONDS).myRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(value("SELECT count(*) FROM workspace_members WHERE workspace_id = '" + workspaceId
                + "' AND user_id = '" + guest.getId() + "'")).isEqualTo("1");
        // Y otra vez más tarde (el enlace del correo abierto de nuevo): sigue llevando al workspace
        assertThat(invitationService.accept(token, guest.getId()).slug()).isEqualTo(slug);
    }

    @Test
    void unaInvitacionAceptadaNoSirveAOtraPersonaNiAQuienYaSalio() {
        User guest = user("invitada-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com", "Inés Invitada");
        User other = user("otra-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com", "Otra Persona");
        String token = invitation(guest.getEmail(), "7 days", false);
        invitationService.accept(token, guest.getId());

        assertThatThrownBy(() -> invitationService.accept(token, other.getId()))
                .isInstanceOf(BadRequestException.class);
        workspaceService.removeMember(slug, pm.getId(), guest.getId());
        assertThatThrownBy(() -> invitationService.accept(token, guest.getId()))
                .isInstanceOf(BadRequestException.class);
        assertThat(value("SELECT count(*) FROM workspace_members WHERE workspace_id = '" + workspaceId
                + "' AND user_id = '" + guest.getId() + "'")).isEqualTo("0");
    }

    /** La limpieza diaria borra las invitaciones vencidas, aceptadas o no (antes no se borraban nunca). */
    @Test
    void laLimpiezaBorraLasInvitacionesVencidas() {
        String expiredPending = invitation("a-" + UUID.randomUUID() + "@test.com", "-1 day", false);
        String expiredAccepted = invitation("b-" + UUID.randomUUID() + "@test.com", "-1 minute", true);
        String pending = invitation("c-" + UUID.randomUUID() + "@test.com", "7 days", false);
        String accepted = invitation("d-" + UUID.randomUUID() + "@test.com", "2 days", true);

        maintenanceJobs.purge();

        for (String gone : new String[] {expiredPending, expiredAccepted}) {
            assertThat(value("SELECT count(*) FROM workspace_invitations WHERE token_hash = '"
                    + TokenUtils.sha256(gone) + "'")).isEqualTo("0");
        }
        for (String kept : new String[] {pending, accepted}) {
            assertThat(value("SELECT count(*) FROM workspace_invitations WHERE token_hash = '"
                    + TokenUtils.sha256(kept) + "'")).isEqualTo("1");
        }
    }

    // ----- Logout -----

    private AuthResponse login() {
        return authService.login(new LoginRequest(pm.getEmail(), "Clave1234"), new ClientInfo("127.0.0.1", "JUnit"));
    }

    /**
     * Una pestaña se quedó con un token de dos rotaciones atrás y la otra cerró sesión. Antes el logout borraba los
     * sucesores y un solo paso hacia atrás: el token viejo quedaba apuntando a un sucesor borrado y reusarlo dentro del
     * margen contaba como robo, así que también se cerraba la sesión del otro equipo.
     */
    @Test
    void reusarUnTokenDeDosRotacionesAtrasTrasElLogoutNoCierraLosOtrosEquipos() {
        AuthResponse otherDevice = login();
        AuthResponse first = login();
        AuthResponse second = authService.refresh(first.refreshToken());
        AuthResponse third = authService.refresh(second.refreshToken());
        authService.logout(third.refreshToken());

        assertThatThrownBy(() -> authService.refresh(first.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(value("SELECT count(*) FROM refresh_tokens WHERE user_id = '" + pm.getId() + "'")).isEqualTo("1");
        assertThat(authService.refresh(otherDevice.refreshToken()).accessToken()).isNotBlank();
    }

    /** Cerrar sesión con el token más viejo de la cadena también borra todo lo que vino después. */
    @Test
    void elLogoutConElTokenMasViejoBorraTodaLaCadena() {
        AuthResponse otherDevice = login();
        AuthResponse first = login();
        AuthResponse second = authService.refresh(first.refreshToken());
        AuthResponse third = authService.refresh(second.refreshToken());
        authService.logout(first.refreshToken());

        for (AuthResponse dead : new AuthResponse[] {second, third}) {
            assertThatThrownBy(() -> authService.refresh(dead.refreshToken()))
                    .isInstanceOf(UnauthorizedException.class);
        }
        assertThat(authService.refresh(otherDevice.refreshToken()).accessToken()).isNotBlank();
    }
}
