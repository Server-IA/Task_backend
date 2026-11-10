package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.ReportOptions;
import com.taskflow.api.dto.response.IssuesReportPreview;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.report.IssuesReport;
import com.taskflow.api.report.IssuesReport.Count;
import com.taskflow.api.report.IssuesReport.FilterLine;
import com.taskflow.api.report.IssuesReport.Group;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.repository.ProjectModuleRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.ReportService.ReportFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reporte de tareas: totales, repartos, grupos, filtros legibles y validación de los rangos de fechas.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    private static final String SLUG = "equipo";
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final IssueFilter NO_FILTER = new IssueFilter(null, null, null, null, null, null, null, null, null);
    private static final ReportOptions DEFAULTS = new ReportOptions(null, null, null, null, null, null, null, null);

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private StateRepository stateRepository;
    @Mock
    private LabelRepository labelRepository;
    @Mock
    private CycleRepository cycleRepository;
    @Mock
    private ProjectModuleRepository moduleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ResourceLookup lookup;

    private ReportServiceImpl service;
    private Project project;
    private User ana;
    private User luis;
    private State todo;
    private State doing;
    private State done;
    private State cancelled;
    private final List<Issue> issues = new ArrayList<>();
    private int sequence;

    @BeforeEach
    void setUp() {
        service = new ReportServiceImpl(issueRepository, stateRepository, labelRepository, cycleRepository,
                moduleRepository, userRepository, lookup, new AppProperties(null, null, null, "America/Bogota", null));
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("Equipo Producto");
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setName("Portal de Clientes");
        project.setIdentifier("PORT");
        project.setWorkspace(workspace);
        ana = user("Ana Ruiz");
        luis = user("Luis Muñoz");
        todo = state("Todo", StateGroup.UNSTARTED);
        doing = state("In Progress", StateGroup.STARTED);
        done = state("Done", StateGroup.COMPLETED);
        cancelled = state("Cancelled", StateGroup.CANCELLED);
        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(lookup.user(ana.getId())).thenReturn(ana);
        lenient().when(stateRepository.findAllByProjectIdOrderByPositionAsc(project.getId()))
                .thenReturn(List.of(todo, doing, done, cancelled));
        lenient().when(issueRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(issues, invocation.getArgument(1), issues.size()));
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
    }

    private static State state(String name, StateGroup group) {
        State state = new State();
        state.setId(UUID.randomUUID());
        state.setName(name);
        state.setStateGroup(group);
        return state;
    }

    private Issue issue(String title, State state, IssuePriority priority, LocalDate dueDate, User... assignees) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setSequenceId(++sequence);
        issue.setTitle(title);
        issue.setState(state);
        issue.setPriority(priority);
        issue.setDueDate(dueDate);
        issue.getAssignees().addAll(List.of(assignees));
        issues.add(issue);
        return issue;
    }

    private IssuesReport build(IssueFilter filter, ReportOptions options) {
        return service.build(SLUG, project.getId(), ana.getId(), filter, options);
    }

    @Test
    void cuentaAbiertasCompletadasCanceladasYVencidas() {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        issue("Vencida", todo, IssuePriority.HIGH, yesterday, ana);
        issue("Para hoy", doing, IssuePriority.HIGH, LocalDate.now(ZONE), ana, luis);
        issue("Hecha tarde", done, IssuePriority.LOW, yesterday);
        issue("Cancelada", cancelled, IssuePriority.NONE, yesterday);

        IssuesReport report = build(NO_FILTER, DEFAULTS);

        // Una completada o cancelada con la fecha pasada no cuenta como vencida, ni la que vence hoy
        assertThat(report.totals()).isEqualTo(new IssuesReport.Totals(4, 2, 1, 1, 1));
        assertThat(report.byState()).containsExactly(new Count("Todo", 1), new Count("In Progress", 1),
                new Count("Done", 1), new Count("Cancelled", 1));
        assertThat(report.byPriority()).containsExactly(new Count("Alta", 2), new Count("Baja", 1),
                new Count("Sin prioridad", 1));
        // Una tarea con dos asignados cuenta para ambos; «Sin asignar» va al final
        assertThat(report.byAssignee()).containsExactly(new Count("Ana Ruiz", 2), new Count("Luis Muñoz", 1),
                new Count("Sin asignar", 2));
        assertThat(report.byType()).containsExactly(new Count("Tarea", 4));
        assertThat(report.projectName()).isEqualTo("Portal de Clientes");
        assertThat(report.workspaceName()).isEqualTo("Equipo Producto");
        assertThat(report.generatedBy()).isEqualTo("Ana Ruiz");
        assertThat(report.truncated()).isFalse();
    }

    @Test
    void agrupaPorEstadoEnElOrdenDelTableroYSinGruposVacios() {
        issue("B", doing, IssuePriority.NONE, null);
        issue("A", todo, IssuePriority.NONE, null);
        issue("C", doing, IssuePriority.NONE, null);

        List<Group> groups = build(NO_FILTER, DEFAULTS).groups();

        assertThat(groups).extracting(Group::title).containsExactly("Todo", "In Progress");
        assertThat(groups.get(1).rows()).extracting(row -> row.title()).containsExactly("B", "C");
    }

    @Test
    void agrupaPorPersonaConSinAsignarAlFinal() {
        issue("De los dos", todo, IssuePriority.NONE, null, luis, ana);
        issue("Suelta", todo, IssuePriority.NONE, null);

        List<Group> groups = build(NO_FILTER,
                new ReportOptions(null, null, null, null, ReportOptions.GroupBy.ASSIGNEE, null, null, null)).groups();

        assertThat(groups).extracting(Group::title).containsExactly("Ana Ruiz", "Luis Muñoz", "Sin asignar");
        assertThat(groups.get(0).rows().get(0).assignees()).containsExactly("Ana Ruiz", "Luis Muñoz");
    }

    @Test
    void sinAgruparHayUnSoloGrupoSinTitulo() {
        Issue bug = issue("Falla", todo, IssuePriority.URGENT, null);
        bug.setType(IssueType.BUG);
        Label label = new Label();
        label.setName("frontend");
        bug.getLabels().add(label);

        List<Group> groups = build(NO_FILTER,
                new ReportOptions(null, null, null, null, ReportOptions.GroupBy.NONE, null, null, null)).groups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).title()).isNull();
        IssuesReport.Row row = groups.get(0).rows().get(0);
        assertThat(row.code()).isEqualTo("PORT-1");
        assertThat(row.type()).isEqualTo("Bug");
        assertThat(row.priority()).isEqualTo("Urgente");
        assertThat(row.labels()).containsExactly("frontend");
    }

    @Test
    void describeLosFiltrosConNombres() {
        UUID goneState = UUID.randomUUID();
        when(userRepository.findAllByIdInWorkspace(List.of(luis.getId()), project.getWorkspace().getId()))
                .thenReturn(List.of(luis));
        when(cycleRepository.findByIdAndProjectId(any(), any())).thenReturn(Optional.empty());

        List<FilterLine> filters = build(
                new IssueFilter(List.of(doing.getId(), todo.getId(), goneState), List.of(IssuePriority.LOW, IssuePriority.URGENT),
                        List.of(luis.getId()), null, "  login ", UUID.randomUUID(), null, true, List.of(IssueType.BUG)),
                new ReportOptions(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10), null, LocalDate.of(2026, 12, 1),
                        null, null, null, null)).filters();

        assertThat(filters).containsExactly(
                new FilterLine("Estado", "Todo, In Progress (ya no existe)"),
                new FilterLine("Prioridad", "Urgente, Baja"),
                new FilterLine("Tipo", "Bug"),
                new FilterLine("Asignado", "Luis Muñoz"),
                new FilterLine("Ciclo", "(ya no existe)"),
                new FilterLine("Bloqueo", "solo bloqueadas"),
                new FilterLine("Búsqueda", "«login»"),
                new FilterLine("Creadas", "del 1 nov 2026 al 10 nov 2026"),
                new FilterLine("Fecha límite", "hasta el 1 dic 2026"));
    }

    @Test
    void sinFiltrosNoHayLineas() {
        assertThat(build(NO_FILTER, DEFAULTS).filters()).isEmpty();
    }

    @Test
    void pideComoMuchoElMaximoDeTareasYAvisaSiHayMas() {
        issue("Única", todo, IssuePriority.NONE, null);
        when(issueRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(issues, invocation.getArgument(1), 1500));

        IssuesReportPreview preview = service.previewIssues(SLUG, project.getId(), ana.getId(), NO_FILTER, DEFAULTS);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(issueRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(IssuesReport.MAX_ISSUES);
        // Por defecto ordena por prioridad (y el listado le suma fecha límite y número)
        assertThat(pageable.getValue().getSort().getOrderFor("priorityRank")).isNotNull();
        assertThat(preview.totalMatching()).isEqualTo(1500);
        assertThat(preview.truncated()).isTrue();
        assertThat(preview.maxIssues()).isEqualTo(1000);
    }

    @Test
    void rechazaUnRangoAlRevesOUnAnioExtremo() {
        assertThatThrownBy(() -> build(NO_FILTER,
                new ReportOptions(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 1), null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("«desde» no puede ser posterior");
        assertThatThrownBy(() -> build(NO_FILTER,
                new ReportOptions(null, null, LocalDate.of(-5, 1, 1), null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1900 y 2999");
        verifyNoInteractions(issueRepository);
    }

    @Test
    void elPdfLlevaNombreDeArchivoYNoSePuedePedirSinSecciones() {
        issue("Única", todo, IssuePriority.NONE, null);

        ReportFile file = service.issuesPdf(SLUG, project.getId(), ana.getId(), NO_FILTER, DEFAULTS);

        assertThat(file.fileName()).isEqualTo("reporte-tareas-PORT-" + LocalDate.now(ZONE) + ".pdf");
        assertThat(file.content()).isNotEmpty();
        assertThatThrownBy(() -> service.issuesPdf(SLUG, project.getId(), ana.getId(), NO_FILTER,
                new ReportOptions(null, null, null, null, null, null, false, false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("resumen, el listado o ambos");
    }
}
