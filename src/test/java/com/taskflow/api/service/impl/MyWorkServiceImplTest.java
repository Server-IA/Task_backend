package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.MyWorkView;
import com.taskflow.api.dto.response.MyWorkResponse;
import com.taskflow.api.dto.response.MyWorkResponse.StateCount;
import com.taskflow.api.dto.response.RelatedIssueResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyWorkServiceImplTest {

    private static final String SLUG = "mi-equipo";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Bogota"));

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ProjectAccess projectAccess;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private IssueMapper issueMapper;

    private MyWorkServiceImpl myWorkService;

    private final UUID userId = UUID.randomUUID();
    private final List<UUID> projectIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    private Workspace workspace;
    private Project web;
    private Project app;
    private State webTodo;
    private State webInProgress;
    private State webReview;
    private State appInProgress;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null, null, "http://localhost:5173", "America/Bogota", null);
        myWorkService = new MyWorkServiceImpl(issueRepository, projectAccess, lookup, issueMapper, properties);
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        web = project(projectIds.get(0), "WEB");
        app = project(projectIds.get(1), "AMC");
        webTodo = new State(web, "Por hacer", "#3A3A3A", StateGroup.UNSTARTED, 1);
        webInProgress = new State(web, "En curso", "#F59E0B", StateGroup.STARTED, 2);
        webReview = new State(web, "En revisión", "#8B5CF6", StateGroup.STARTED, 3);
        appInProgress = new State(app, "en curso", "#D9700F", StateGroup.STARTED, 4);
        lenient().when(issueMapper.toRelated(any(Issue.class))).thenAnswer(invocation -> {
            Issue issue = invocation.getArgument(0);
            return new RelatedIssueResponse(issue.getId(), issue.getCode(), issue.getTitle(), null, List.of(), null,
                    issue.getDueDate(), 0, 0, 0, 0, 0);
        });
    }

    private static Project project(UUID id, String identifier) {
        Project project = new Project();
        project.setId(id);
        project.setIdentifier(identifier);
        return project;
    }

    private void accessibleProjects(List<UUID> ids) {
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        when(projectAccess.projectIds(workspace, userId)).thenReturn(ids);
    }

    private Issue issue(int sequenceId, LocalDate dueDate, IssuePriority priority) {
        return issue(webTodo, sequenceId, dueDate, priority);
    }

    private static Issue issue(State state, int sequenceId, LocalDate dueDate, IssuePriority priority) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(state.getProject());
        issue.setState(state);
        issue.setSequenceId(sequenceId);
        issue.setDueDate(dueDate);
        issue.setPriority(priority);
        return issue;
    }

    private void assigned(Issue... issues) {
        when(issueRepository.findAssignedTo(projectIds, userId, StateGroup.CLOSED))
                .thenReturn(new ArrayList<>(List.of(issues)));
    }

    private void counts() {
        when(issueRepository.countCreatedBy(projectIds, userId, StateGroup.CLOSED)).thenReturn(4L);
        when(issueRepository.countCommentedBy(projectIds, userId, StateGroup.CLOSED)).thenReturn(3L);
        when(issueRepository.countAssignedCompletedSince(eq(projectIds), eq(userId), any(Instant.class)))
                .thenReturn(2L);
    }

    @Test
    void vistaAsignadasConLosContadoresDeLasTresVistas() {
        accessibleProjects(projectIds);
        counts();
        assigned(issue(webInProgress, 1, null, IssuePriority.NONE));

        MyWorkResponse response = myWorkService.getMyWork(SLUG, userId, MyWorkView.ASSIGNED);

        assertThat(response.issues()).hasSize(1);
        assertThat(response.assignedCount()).isEqualTo(1);
        assertThat(response.createdCount()).isEqualTo(4);
        assertThat(response.commentedCount()).isEqualTo(3);
        assertThat(response.completedThisWeek()).isEqualTo(2);
        assertThat(response.inProgressCount()).isEqualTo(1);
        verify(issueRepository, never()).findCreatedBy(any(), any(), any());
        verify(issueRepository, never()).findCommentedBy(any(), any(), any());
    }

    @Test
    void vistaCreadasConLosContadoresDeLasAsignadas() {
        accessibleProjects(projectIds);
        counts();
        assigned(issue(1, null, IssuePriority.NONE), issue(2, null, IssuePriority.NONE));
        when(issueRepository.findCreatedBy(projectIds, userId, StateGroup.CLOSED)).thenReturn(List.of());

        MyWorkResponse response = myWorkService.getMyWork(SLUG, userId, MyWorkView.CREATED);

        verify(issueRepository).findCreatedBy(projectIds, userId, StateGroup.CLOSED);
        verify(issueRepository, never()).findCommentedBy(any(), any(), any());
        // La lista es la de creadas, pero «tu semana» sigue contando las asignadas
        assertThat(response.issues()).isEmpty();
        assertThat(response.assignedCount()).isEqualTo(2);
    }

    @Test
    void vistaComentadas() {
        accessibleProjects(projectIds);
        counts();
        assigned();
        when(issueRepository.findCommentedBy(projectIds, userId, StateGroup.CLOSED)).thenReturn(List.of());

        myWorkService.getMyWork(SLUG, userId, MyWorkView.COMMENTED);

        verify(issueRepository).findCommentedBy(projectIds, userId, StateGroup.CLOSED);
        verify(issueRepository, never()).findCreatedBy(any(), any(), any());
    }

    @Test
    void ordenaPorFechaLimitePrioridadYNumero() {
        accessibleProjects(projectIds);
        counts();
        LocalDate soon = LocalDate.of(2026, 10, 28);
        Issue noDueUrgent = issue(1, null, IssuePriority.URGENT);
        Issue laterLow = issue(2, soon.plusDays(3), IssuePriority.LOW);
        Issue soonLow = issue(3, soon, IssuePriority.LOW);
        Issue soonUrgent = issue(9, soon, IssuePriority.URGENT);
        Issue soonUrgentFirst = issue(4, soon, IssuePriority.URGENT);
        Issue noDueNone = issue(5, null, IssuePriority.NONE);
        assigned(noDueNone, laterLow, soonUrgent, noDueUrgent, soonLow, soonUrgentFirst);

        myWorkService.getMyWork(SLUG, userId, MyWorkView.ASSIGNED);

        ArgumentCaptor<Issue> mapped = ArgumentCaptor.forClass(Issue.class);
        verify(issueMapper, times(6)).toResponse(mapped.capture());
        assertThat(mapped.getAllValues()).containsExactly(
                soonUrgentFirst, soonUrgent, soonLow, laterLow, noDueUrgent, noDueNone);
    }

    @Test
    void tuSemanaCuentaPorEstadoVencidasProyectosYLaProximaFecha() {
        accessibleProjects(projectIds);
        counts();
        Issue overdue = issue(webInProgress, 2, TODAY.minusDays(2), IssuePriority.URGENT);
        Issue dueToday = issue(webReview, 7, TODAY, IssuePriority.HIGH);
        Issue tomorrow = issue(webTodo, 19, TODAY.plusDays(1), IssuePriority.HIGH);
        Issue nextWeek = issue(appInProgress, 12, TODAY.plusDays(7), IssuePriority.MEDIUM);
        Issue noDate = issue(webTodo, 15, null, IssuePriority.MEDIUM);
        assigned(noDate, nextWeek, tomorrow, dueToday, overdue);

        MyWorkResponse response = myWorkService.getMyWork(SLUG, userId, MyWorkView.ASSIGNED);

        assertThat(response.assignedCount()).isEqualTo(5);
        assertThat(response.overdueCount()).isEqualTo(1);
        assertThat(response.projectCount()).isEqualTo(2);
        // «En curso» de WEB y «en curso» de AMC se suman; «En revisión» va aparte (no se buscan nombres concretos)
        assertThat(response.byState()).containsExactly(
                new StateCount("Por hacer", "#3A3A3A", StateGroup.UNSTARTED, 2),
                new StateCount("En curso", "#F59E0B", StateGroup.STARTED, 2),
                new StateCount("En revisión", "#8B5CF6", StateGroup.STARTED, 1));
        assertThat(response.inProgressCount()).isEqualTo(3);
        // La próxima fecha es la primera después de hoy (las de hoy y las vencidas tienen su grupo)
        assertThat(response.nextDue().id()).isEqualTo(tomorrow.getId());
    }

    @Test
    void sinFechasProximasNoHayNextDue() {
        assertThat(MyWorkServiceImpl.nextDue(List.of(issue(1, TODAY, IssuePriority.NONE),
                issue(2, null, IssuePriority.NONE)), TODAY)).isNull();
    }

    @Test
    void sinProyectosAccesiblesNoConsultaTareas() {
        accessibleProjects(List.of());

        MyWorkResponse response = myWorkService.getMyWork(SLUG, userId, MyWorkView.ASSIGNED);

        assertThat(response.issues()).isEmpty();
        assertThat(response.assignedCount()).isZero();
        assertThat(response.byState()).isEmpty();
        assertThat(response.nextDue()).isNull();
        verifyNoInteractions(issueRepository);
    }

    @Test
    void leeLaVistaDelParametro() {
        assertThat(MyWorkView.from(null)).isEqualTo(MyWorkView.ASSIGNED);
        assertThat(MyWorkView.from(" ")).isEqualTo(MyWorkView.ASSIGNED);
        assertThat(MyWorkView.from("created")).isEqualTo(MyWorkView.CREATED);
        assertThat(MyWorkView.from("Commented")).isEqualTo(MyWorkView.COMMENTED);
        assertThatThrownBy(() -> MyWorkView.from("todas")).isInstanceOf(BadRequestException.class);
    }
}
