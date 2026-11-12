package com.taskflow.api.integration;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.ImportUploadRequest;
import com.taskflow.api.dto.response.ImportJobResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.entity.enums.ImportStatus;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.imports.ImportDefaults.PersonRow;
import com.taskflow.api.imports.ImportMapping;
import com.taskflow.api.imports.ImportMapping.PersonAction;
import com.taskflow.api.imports.ImportMapping.PersonTarget;
import com.taskflow.api.imports.ImportMapping.StateTarget;
import com.taskflow.api.imports.ImportMapping.TypeTarget;
import com.taskflow.api.imports.ImportPendingAssignments;
import com.taskflow.api.imports.ImportSelection;
import com.taskflow.api.imports.ImportSelection.CsvSetup;
import com.taskflow.api.imports.ImportSelection.Options;
import com.taskflow.api.imports.ImportSelection.ProjectChoice;
import com.taskflow.api.imports.csv.CsvField;
import com.taskflow.api.imports.csv.CsvImport;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import com.taskflow.api.service.ImportService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.WorkspaceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Una importación de punta a punta desde un archivo CSV, contra PostgreSQL: lo que se propone, lo que queda escrito,
 * las fechas y los números del origen, las personas invitadas y deshacerla.
 */
@SpringBootTest
class ImportIntegrationTest {

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
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private IssueService issueService;
    @Autowired
    private ImportService importService;
    @Autowired
    private ImportPendingAssignments pendingAssignments;
    @Autowired
    private TransactionTemplate transaction;

    private String suffix;
    private User pm;
    private User luis;
    private String slug;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = user("pm-" + suffix + "@test.com", "Paula PM");
        luis = user("luis-" + suffix + "@test.com", "Luis Muñoz");
        slug = workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Equipo " + suffix, "eq-" + suffix)).slug();
        workspaceMember(luis);
    }

    private String jiraCsv() {
        return """
                Issue key,Summary,Issue Type,Status,Priority,Assignee,Labels,Labels,Parent,Sprint,Created,Resolved,Description,Comment,Story Points
                PORT-1,Pedidos,Epic,In Progress,High,,,,,,2026-09-01,,Todo lo de pedidos,,
                PORT-2,Ver el historial de pedidos,Story,In Progress,High,%s,backend,pedidos,PORT-1,Sprint 1,2026-09-02 10:00,,Como cliente quiero ver mis pedidos,,5
                PORT-3,Exportar el historial a CSV,Sub-task,Done,Medium,%s,backend,,PORT-2,Sprint 1,2026-09-03,2026-09-10,El detalle,"2026-09-04 09:00;Marta Gil;Buen trabajo",
                PORT-7,El total no incluye el descuento,Bug,To Do,Highest,marta-%s@fuera.com,,,PORT-2,,2026-09-05,,,,
                PORT-9,Pruebas de carga,Task,In QA,Low,,,,,,2026-09-06,,,,
                """.formatted(luis.getEmail(), luis.getEmail(), suffix);
    }

    @Test
    void importaUnCsvAUnProyectoNuevoConSuJerarquiaSusNumerosYSusFechas() {
        ImportJobResponse uploaded = upload("jira-portal.csv", jiraCsv());

        assertThat(uploaded.status()).isEqualTo(ImportStatus.DRAFT);
        assertThat(uploaded.discovery().csv().rows()).isEqualTo(5);
        assertThat(uploaded.discovery().csv().columns()).extracting(CsvImport.Column::suggested).containsExactly(
                CsvField.KEY, CsvField.TITLE, CsvField.TYPE, CsvField.STATUS, CsvField.PRIORITY, CsvField.ASSIGNEE, CsvField.LABELS,
                CsvField.LABELS, CsvField.PARENT, CsvField.SPRINT, CsvField.CREATED_AT, CsvField.COMPLETED_AT, CsvField.DESCRIPTION,
                CsvField.COMMENT, CsvField.POINTS);
        assertThat(uploaded.discovery().projects()).singleElement().satisfies(project -> {
            assertThat(project.key()).isEqualTo("PORT");
            assertThat(project.suggestedIdentifier()).isEqualTo("PORT");
        });

        ImportJobResponse ready = select(uploaded, new ProjectChoice(uploaded.discovery().projects().get(0).id(), null, "Portal de clientes", "PORT"));

        // Lo que se propone sin tocar nada
        assertThat(ready.mapping().types()).extracting(row -> row.source() + "→" + row.target())
                .containsExactly("Epic→MODULE", "Story→STORY", "Sub-task→TASK", "Bug→BUG", "Task→TASK");
        assertThat(ready.mapping().states()).extracting(row -> row.source() + "→" + row.target().name() + "/" + row.target().group())
                .containsExactlyInAnyOrder("In Progress→In Progress/STARTED", "Done→Done/COMPLETED", "To Do→Todo/UNSTARTED",
                        "In QA→In QA/STARTED");
        PersonRow luisRow = person(ready, luis.getEmail());
        assertThat(luisRow.target().action()).isEqualTo(PersonAction.MEMBER);
        assertThat(luisRow.target().userId()).isEqualTo(luis.getId());
        assertThat(luisRow.matchedName()).isEqualTo("Luis Muñoz");
        PersonRow martaRow = person(ready, "marta-" + suffix + "@fuera.com");
        assertThat(martaRow.target().action()).isEqualTo(PersonAction.UNASSIGNED);
        assertThat(ready.preview().totals().unassigned()).isEqualTo(1);

        // A Marta se la invita
        Map<String, PersonTarget> people = new LinkedHashMap<>();
        ready.mapping().people().forEach(row -> people.put(row.id(), row.target()));
        people.put(martaRow.id(), new PersonTarget(PersonAction.INVITE, null, martaRow.email(), null));
        ImportJobResponse mapped = importService.saveMapping(slug, ready.id(), pm.getId(), new ImportMapping(null, null, null, people));

        assertThat(mapped.preview().totals()).satisfies(totals -> {
            assertThat(totals.projects()).isEqualTo(1);
            assertThat(totals.modules()).isEqualTo(1);
            assertThat(totals.cycles()).isEqualTo(1);
            assertThat(totals.stories()).isEqualTo(1);
            assertThat(totals.tasks()).isEqualTo(2);
            assertThat(totals.bugs()).isEqualTo(1);
            assertThat(totals.comments()).isEqualTo(1);
            assertThat(totals.withPoints()).isEqualTo(1);
            assertThat(totals.unassigned()).isZero();
            assertThat(totals.invitations()).isEqualTo(1);
        });
        assertThat(mapped.preview().newStates()).containsExactly(new StateTarget("In QA", StateGroup.STARTED));
        assertThat(mapped.preview().labels()).isEqualTo(2);
        assertThat(mapped.preview().sample()).extracting(ImportJobResponse.Sample::code).contains("PORT-2", "PORT-3", "PORT-7");

        importService.start(slug, ready.id(), pm.getId());
        ImportJobResponse done = await(ready.id(), ImportStatus.COMPLETED);

        assertThat(done.report().stories()).isEqualTo(1);
        assertThat(done.report().tasks()).isEqualTo(2);
        assertThat(done.report().bugs()).isEqualTo(1);
        assertThat(done.report().comments()).isEqualTo(1);
        assertThat(done.report().invitations()).isEqualTo(1);
        assertThat(done.report().skippedTotal()).isZero();
        assertThat(done.report().projects()).singleElement().satisfies(project -> {
            assertThat(project.identifier()).isEqualTo("PORT");
            assertThat(project.issues()).isEqualTo(4);
            assertThat(project.created()).isTrue();
        });
        String projectId = done.report().projects().get(0).id().toString();

        // Los números del origen se conservan y el contador sigue después del más alto
        assertThat(values("SELECT sequence_id FROM issues WHERE project_id = '" + projectId + "' ORDER BY sequence_id"))
                .containsExactly("2", "3", "7", "9");
        IssueResponse next = issueService.create(slug, UUID.fromString(projectId), pm.getId(),
                new CreateIssueRequest("La siguiente", null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThat(next.code()).isEqualTo("PORT-10");

        // La épica es un módulo; la historia entra a él y al ciclo; la subtarea cuelga de la historia
        assertThat(value("SELECT name FROM modules WHERE project_id = '" + projectId + "'")).isEqualTo("Pedidos");
        assertThat(value(issue(projectId, 2, "issue_type"))).isEqualTo("STORY");
        assertThat(value("SELECT count(*) FROM module_issues mi JOIN issues i ON i.id = mi.issue_id WHERE i.project_id = '" + projectId
                + "' AND i.sequence_id = 2")).isEqualTo("1");
        assertThat(values("SELECT i.sequence_id FROM cycle_issues ci JOIN issues i ON i.id = ci.issue_id JOIN cycles c ON c.id = ci.cycle_id "
                + "WHERE c.project_id = '" + projectId + "' AND c.name = 'Sprint 1' ORDER BY 1")).containsExactly("2", "3");
        assertThat(value("SELECT p.sequence_id FROM issues i JOIN issues p ON p.id = i.parent_id WHERE i.project_id = '" + projectId
                + "' AND i.sequence_id = 3")).isEqualTo("2");
        assertThat(value("SELECT p.sequence_id FROM issues i JOIN issues p ON p.id = i.parent_id WHERE i.project_id = '" + projectId
                + "' AND i.sequence_id = 7")).isEqualTo("2");

        // Estado, prioridad y fechas del origen
        assertThat(value("SELECT s.name FROM issues i JOIN states s ON s.id = i.state_id WHERE i.project_id = '" + projectId
                + "' AND i.sequence_id = 9")).isEqualTo("In QA");
        assertThat(values("SELECT name FROM states WHERE project_id = '" + projectId + "' ORDER BY position"))
                .containsExactly("Backlog", "Todo", "In Progress", "In QA", "Done", "Cancelled");
        assertThat(value(issue(projectId, 7, "priority"))).isEqualTo(IssuePriority.URGENT.name());
        assertThat(value(issue(projectId, 3, "to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')"))).isEqualTo("2026-09-03");
        assertThat(value(issue(projectId, 3, "to_char(completed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')"))).isEqualTo("2026-09-10");
        assertThat(value(issue(projectId, 2, "to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI')"))).isEqualTo("2026-09-02 10:00");
        assertThat(value(issue(projectId, 2, "import_source || ' ' || import_key"))).isEqualTo("JIRA PORT-2");
        assertThat(value(issue(projectId, 2, "description"))).contains("Como cliente quiero ver mis pedidos").contains("Story points:** 5");

        // Etiquetas, asignados y el comentario con el nombre de quien lo escribió
        assertThat(values("SELECT name FROM labels WHERE project_id = '" + projectId + "' ORDER BY name")).containsExactly("backend", "pedidos");
        assertThat(values("SELECT i.sequence_id FROM issue_assignees a JOIN issues i ON i.id = a.issue_id WHERE i.project_id = '" + projectId
                + "' AND a.user_id = '" + luis.getId() + "' ORDER BY 1")).containsExactly("2", "3");
        assertThat(value("SELECT count(*) FROM project_members WHERE project_id = '" + projectId + "' AND user_id = '" + luis.getId() + "'"))
                .isEqualTo("1");
        assertThat(value("SELECT c.body FROM comments c JOIN issues i ON i.id = c.issue_id WHERE i.project_id = '" + projectId + "'"))
                .startsWith("**Marta Gil** (Jira, 2026-09-04):").endsWith("Buen trabajo");
        assertThat(value("SELECT to_char(c.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') FROM comments c JOIN issues i ON i.id = c.issue_id "
                + "WHERE i.project_id = '" + projectId + "'")).isEqualTo("2026-09-04");

        // A quien lanzó la importación le llega el aviso
        assertThat(value("SELECT message FROM notifications WHERE recipient_id = '" + pm.getId() + "' AND type = 'IMPORT_FINISHED'"))
                .isEqualTo("La importación desde Jira terminó. Tareas: 4 · proyectos: 1. El informe está en Ajustes › Importar.");

        // Marta recibió su invitación; cuando entra, el bug es suyo y ella es del proyecto
        assertThat(value("SELECT count(*) FROM workspace_invitations WHERE email = 'marta-" + suffix + "@fuera.com'")).isEqualTo("1");
        User marta = user("marta-" + suffix + "@fuera.com", "Marta Gil");
        workspaceMember(marta);
        Workspace workspace = workspaceRepository.findBySlug(slug).orElseThrow();
        transaction.executeWithoutResult(status -> pendingAssignments.apply(workspace, marta));
        assertThat(values("SELECT i.sequence_id FROM issue_assignees a JOIN issues i ON i.id = a.issue_id WHERE a.user_id = '" + marta.getId() + "'"))
                .containsExactly("7");
        assertThat(value("SELECT count(*) FROM project_members WHERE project_id = '" + projectId + "' AND user_id = '" + marta.getId() + "'"))
                .isEqualTo("1");

        // Deshacer se lleva el proyecto entero
        importService.undo(slug, ready.id());
        await(ready.id(), ImportStatus.UNDONE);
        assertThat(value("SELECT count(*) FROM projects WHERE id = '" + projectId + "'")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM issues WHERE project_id = '" + projectId + "'")).isEqualTo("0");
    }

    @Test
    void importaAUnProyectoQueYaExisteSinTocarLoQueTeniaYDeshacerSoloQuitaLoImportado() {
        UUID web = projectService.create(slug, pm.getId(), new CreateProjectRequest("Web", "W" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X"), null, null)).id();
        IssueResponse mine = issueService.create(slug, web, pm.getId(),
                new CreateIssueRequest("La que ya estaba", null, null, null, null, null, null, null, null, null, null, null, null, null));

        ImportJobResponse uploaded = upload("tareas.csv", """
                Título;Estado;Etiquetas;Tarea padre
                Rediseñar el boletín;En curso;diseño, correo;
                Maquetar la plantilla;Pendiente;diseño;Rediseñar el boletín
                Campaña cancelada;Cancelada;;
                """);
        ImportJobResponse ready = select(uploaded, new ProjectChoice(uploaded.discovery().projects().get(0).id(), web, null, null));

        assertThat(ready.preview().totals().projects()).isZero();
        assertThat(ready.preview().totals().tasks()).isEqualTo(3);
        assertThat(ready.preview().newStates()).isEmpty();

        importService.start(slug, ready.id(), pm.getId());
        ImportJobResponse done = await(ready.id(), ImportStatus.COMPLETED);

        assertThat(done.report().projects()).singleElement().satisfies(project -> assertThat(project.created()).isFalse());
        // Los números siguen a los del proyecto: la tarea que ya estaba es la 1
        assertThat(values("SELECT sequence_id || ' ' || title FROM issues WHERE project_id = '" + web + "' ORDER BY sequence_id"))
                .containsExactly("1 La que ya estaba", "2 Rediseñar el boletín", "3 Maquetar la plantilla", "4 Campaña cancelada");
        assertThat(value("SELECT p.title FROM issues i JOIN issues p ON p.id = i.parent_id WHERE i.project_id = '" + web + "' AND i.sequence_id = 3"))
                .isEqualTo("Rediseñar el boletín");
        assertThat(values("SELECT s.state_group FROM issues i JOIN states s ON s.id = i.state_id WHERE i.project_id = '" + web
                + "' AND i.sequence_id > 1 ORDER BY i.sequence_id")).containsExactly("STARTED", "BACKLOG", "CANCELLED");
        assertThat(values("SELECT name FROM labels WHERE project_id = '" + web + "' ORDER BY name")).containsExactly("correo", "diseño");

        // Una importación que ya trajo datos no se descarta
        assertThatThrownBy(() -> importService.discard(slug, ready.id())).isInstanceOf(ConflictException.class);

        importService.undo(slug, ready.id());
        await(ready.id(), ImportStatus.UNDONE);

        assertThat(values("SELECT title FROM issues WHERE project_id = '" + web + "'")).containsExactly("La que ya estaba");
        assertThat(value("SELECT count(*) FROM labels WHERE project_id = '" + web + "'")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM projects WHERE id = '" + web + "'")).isEqualTo("1");
        assertThat(mine.code()).endsWith("-1");
    }

    @Test
    void conUnTipoDescartadoYSinLoTerminadoSoloEntraLoQueQueda() {
        ImportJobResponse uploaded = upload("jira-portal.csv", jiraCsv());
        ProjectChoice choice = new ProjectChoice(uploaded.discovery().projects().get(0).id(), null, "Portal", "PRT");
        List<CsvField> columns = uploaded.discovery().csv().columns().stream().map(CsvImport.Column::suggested).toList();
        importService.select(slug, uploaded.id(), new ImportSelection(List.of(choice), new Options(false, false, true, true, true, false),
                new CsvSetup(columns)));
        ImportJobResponse ready = await(uploaded.id(), ImportStatus.READY);
        ImportJobResponse mapped = importService.saveMapping(slug, ready.id(), pm.getId(),
                new ImportMapping(Map.of("Bug", TypeTarget.SKIP, "Epic", TypeTarget.STORY), null, null, null));

        // Sin la subtarea terminada y sin el bug; la épica ahora es una historia y la historia que colgaba de ella queda suelta
        assertThat(mapped.preview().totals().stories()).isEqualTo(2);
        assertThat(mapped.preview().totals().tasks()).isEqualTo(1);
        assertThat(mapped.preview().totals().bugs()).isZero();
        assertThat(mapped.preview().totals().modules()).isZero();

        importService.start(slug, ready.id(), pm.getId());
        ImportJobResponse done = await(ready.id(), ImportStatus.COMPLETED);
        String projectId = done.report().projects().get(0).id().toString();

        // Sin conservar los números, van seguidos
        assertThat(values("SELECT sequence_id || ' ' || issue_type || ' ' || title FROM issues WHERE project_id = '" + projectId
                + "' ORDER BY sequence_id")).containsExactly("1 STORY Pedidos", "2 STORY Ver el historial de pedidos", "3 TASK Pruebas de carga");
        assertThat(value("SELECT count(*) FROM issues WHERE project_id = '" + projectId + "' AND parent_id IS NOT NULL")).isEqualTo("0");
    }

    // ----- Utilidades

    private ImportJobResponse upload(String fileName, String csv) {
        return importService.upload(slug, pm.getId(), new ImportUploadRequest(fileName.startsWith("jira") ? ImportSourceType.JIRA : ImportSourceType.CSV,
                fileName, Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8))));
    }

    /** Elige el proyecto con las columnas tal como se propusieron y espera a que se lea el archivo. */
    private ImportJobResponse select(ImportJobResponse uploaded, ProjectChoice choice) {
        List<CsvField> columns = uploaded.discovery().csv().columns().stream().map(CsvImport.Column::suggested).toList();
        importService.select(slug, uploaded.id(), new ImportSelection(List.of(choice), Options.DEFAULTS, new CsvSetup(columns)));
        return await(uploaded.id(), ImportStatus.READY);
    }

    private ImportJobResponse await(UUID jobId, ImportStatus wanted) {
        for (int attempt = 0; attempt < 300; attempt++) {
            ImportJobResponse job = importService.get(slug, jobId);
            if (job.status() == wanted) {
                return job;
            }
            if (!job.status().working()) {
                throw new AssertionError("La importación quedó en " + job.status() + " (" + job.error() + "), se esperaba " + wanted);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("La importación no llegó a " + wanted);
    }

    private static PersonRow person(ImportJobResponse job, String email) {
        return job.mapping().people().stream().filter(row -> email.equals(row.email())).findFirst().orElseThrow();
    }

    private static String issue(String projectId, int number, String column) {
        return "SELECT " + column + " FROM issues WHERE project_id = '" + projectId + "' AND sequence_id = " + number;
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
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role) SELECT gen_random_uuid(), w.id, '" + user.getId()
                + "', 'MEMBER' FROM workspaces w WHERE slug = '" + slug + "'");
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
        List<String> rows = values(query);
        return rows.isEmpty() ? null : rows.getFirst();
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
}
