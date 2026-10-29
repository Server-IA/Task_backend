package com.taskflow.api.integration;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.response.ChecklistItemResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.ProjectResponse;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.dto.request.CreateStateRequest;
import com.taskflow.api.dto.request.DailyNoteRequest;
import com.taskflow.api.dto.request.LabelRequest;
import com.taskflow.api.dto.response.DailyNoteResponse;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.service.DailyService;
import com.taskflow.api.service.IssueChecklistService;
import com.taskflow.api.service.LabelService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.ProjectService;
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
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Errores que encontró el QA de la API de punta a punta, contra un PostgreSQL de verdad (una base descartable,
 * {@link TestDatabase}).
 */
@SpringBootTest
class ApiQaIntegrationTest {

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
    private IssueChecklistService checklistService;
    @Autowired
    private LabelService labelService;
    @Autowired
    private DailyService dailyService;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    private String suffix;
    private User pm;
    private User member;
    private String slug;
    private UUID projectId;
    private UUID todo;

    @BeforeEach
    void project() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = user("pm-" + suffix + "@test.com", "Paula PM");
        member = user("luis-" + suffix + "@test.com", "Luis Miembro");
        slug = workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Equipo " + suffix, "qa-" + suffix))
                .slug();
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role) SELECT gen_random_uuid(), w.id, '"
                + member.getId() + "', 'MEMBER' FROM workspaces w WHERE slug = '" + slug + "'");
        ProjectResponse project = projectService.create(slug, pm.getId(),
                new CreateProjectRequest("Web", "Q" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X"),
                        null, null));
        projectId = project.id();
        projectService.addMember(slug, projectId, new AddProjectMemberRequest(member.getId()));
        for (StateResponse state : stateService.getStates(slug, projectId)) {
            if (state.group() == StateGroup.UNSTARTED) {
                todo = state.id();
            }
        }
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    // ----- Checklist: el máximo de 100 y las posiciones con peticiones simultáneas -----

    /**
     * Dos «agregar paso» a la vez sobre una tarea con 99 elementos: antes ambas contaban 99 y guardaban, y la tarea
     * quedaba con 101 (en el QA, 60 peticiones en paralelo sobre 50 dejaron 104) y con posiciones repetidas. Ahora la
     * fila de la tarea se bloquea antes de contar: van en fila, una entra y la otra recibe 409.
     */
    @Test
    void agregarPasosALaVezNoPasaDelMaximoNiRepitePosiciones() throws Exception {
        List<ChecklistItemRequest> initial = IntStream.range(0, 99)
                .mapToObj(i -> new ChecklistItemRequest(ChecklistItemKind.CHECK, "paso " + i))
                .toList();
        IssueResponse issue = issueService.create(slug, projectId, pm.getId(), new CreateIssueRequest("Checklist",
                null, todo, null, null, null, null, null, null, initial, null, null, null, null));

        List<Future<ChecklistItemResponse>> calls = new ArrayList<>();
        try (Connection other = lockIssue(issue.id())) {
            for (int i = 0; i < 2; i++) {
                String text = "nuevo " + i;
                calls.add(pool.submit(() -> checklistService.create(slug, projectId, issue.id(),
                        new ChecklistItemRequest(ChecklistItemKind.CHECK, text))));
            }
            awaitLockWaiters(2);
            other.commit();
        }

        int created = 0;
        int conflicts = 0;
        for (Future<ChecklistItemResponse> call : calls) {
            try {
                call.get(20, TimeUnit.SECONDS);
                created++;
            } catch (ExecutionException ex) {
                assertThat(ex.getCause()).isInstanceOf(ConflictException.class);
                conflicts++;
            }
        }
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        List<ChecklistItemResponse> items = checklistService.getItems(slug, projectId, issue.id());
        assertThat(items).hasSize(100);
        assertThat(items.stream().map(ChecklistItemResponse::position).sorted().toList())
                .isEqualTo(IntStream.range(0, 100).boxed().toList());
    }

    /** Un miembro reordena mientras otro agrega: las posiciones siguen siendo 0, 1, 2… sin repetirse. */
    @Test
    void reordenarYAgregarALaVezDejaLasPosicionesSeguidas() throws Exception {
        IssueResponse issue = issueService.create(slug, projectId, pm.getId(), new CreateIssueRequest("Orden",
                null, todo, null, null, null, null, null, null,
                List.of(new ChecklistItemRequest(ChecklistItemKind.CHECK, "a"),
                        new ChecklistItemRequest(ChecklistItemKind.CHECK, "b"),
                        new ChecklistItemRequest(ChecklistItemKind.CHECK, "c")), null, null, null, null));
        UUID last = checklistService.getItems(slug, projectId, issue.id()).getLast().id();

        List<Future<?>> calls = new ArrayList<>();
        try (Connection other = lockIssue(issue.id())) {
            calls.add(pool.submit(() -> checklistService.create(slug, projectId, issue.id(),
                    new ChecklistItemRequest(ChecklistItemKind.CHECK, "d"))));
            calls.add(pool.submit(() -> checklistService.update(slug, projectId, issue.id(), last, member.getId(),
                    new com.taskflow.api.dto.request.UpdateChecklistItemRequest(null, null, 0))));
            awaitLockWaiters(2);
            other.commit();
        }
        for (Future<?> call : calls) {
            call.get(20, TimeUnit.SECONDS);
        }

        List<ChecklistItemResponse> items = checklistService.getItems(slug, projectId, issue.id());
        assertThat(items.stream().map(ChecklistItemResponse::position).toList()).containsExactly(0, 1, 2, 3);
        assertThat(items.getFirst().id()).isEqualTo(last);
    }

    // ----- Nombres sin importar mayúsculas y la primera nota del día, con peticiones simultáneas -----

    /**
     * «Bug» y «bug» a la vez: el servicio compara sin importar mayúsculas, pero la base de datos solo impide el mismo
     * nombre exacto, así que antes las dos entraban (el QA creó 4 etiquetas «Race», «race», «RACE», «rAce»). Ahora se
     * bloquea el proyecto antes de comprobar: una entra y la otra recibe 409.
     */
    @Test
    void dosEtiquetasQueSoloCambianEnMayusculasALaVezNoEntranAmbas() throws Exception {
        List<Future<?>> calls = new ArrayList<>();
        try (Connection other = lockProjectRow()) {
            calls.add(pool.submit(() -> labelService.create(slug, projectId, new LabelRequest("Bug", "#FF0000"))));
            calls.add(pool.submit(() -> labelService.create(slug, projectId, new LabelRequest("bug", "#00FF00"))));
            awaitLockWaiters(2);
            other.commit();
        }
        assertThat(outcomes(calls)).containsExactlyInAnyOrder("ok", "ConflictException");
        assertThat(labelService.getLabels(slug, projectId).stream()
                .filter(label -> label.name().toLowerCase(Locale.ROOT).equals("bug"))).hasSize(1);
    }

    @Test
    void dosEstadosQueSoloCambianEnMayusculasALaVezNoEntranAmbos() throws Exception {
        List<Future<?>> calls = new ArrayList<>();
        try (Connection other = lockProjectRow()) {
            calls.add(pool.submit(() -> stateService.create(slug, projectId,
                    new CreateStateRequest("Review", "#123456", StateGroup.STARTED, null))));
            calls.add(pool.submit(() -> stateService.create(slug, projectId,
                    new CreateStateRequest("REVIEW", "#654321", StateGroup.STARTED, null))));
            awaitLockWaiters(2);
            other.commit();
        }
        assertThat(outcomes(calls)).containsExactlyInAnyOrder("ok", "ConflictException");
        assertThat(stateService.getStates(slug, projectId).stream()
                .filter(state -> state.name().equalsIgnoreCase("review"))).hasSize(1);
    }

    /**
     * Dos personas guardan la primera nota de la daily de un día a la vez: antes las dos la creaban y una recibía 409
     * «conflicto con datos existentes» (uk_daily_notes_day). Ahora la segunda edita la que creó la primera.
     */
    @Test
    void dosPrimerasNotasDelDiaALaVezNoChocan() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 3);
        List<Future<?>> calls = new ArrayList<>();
        try (Connection other = lockProjectRow()) {
            calls.add(pool.submit(() -> dailyService.saveNote(slug, projectId, pm.getId(), day,
                    new DailyNoteRequest("Nota de Paula"))));
            calls.add(pool.submit(() -> dailyService.saveNote(slug, projectId, member.getId(), day,
                    new DailyNoteRequest("Nota de Luis"))));
            awaitLockWaiters(2);
            other.commit();
        }
        assertThat(outcomes(calls)).containsExactly("ok", "ok");
        Optional<DailyNoteResponse> note = Optional.ofNullable(
                dailyService.getDaily(slug, projectId, pm.getId(), day).note());
        assertThat(note).isPresent();
        assertThat(note.get().text()).isIn("Nota de Paula", "Nota de Luis");
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

    private Connection lockProjectRow() throws SQLException {
        Connection connection = TestDatabase.connect();
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT 1 FROM projects WHERE id = '" + projectId + "' FOR UPDATE")) {
            lock.execute();
        }
        return connection;
    }

    // ----- Utilidades -----

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

    /** Una transacción que bloquea la fila de la tarea hasta {@link Connection#commit()}. */
    private static Connection lockIssue(UUID id) throws SQLException {
        Connection connection = TestDatabase.connect();
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT 1 FROM issues WHERE id = '" + id + "' FOR UPDATE")) {
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
}
