package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.MyWorkView;
import com.taskflow.api.dto.response.MyWorkResponse;
import com.taskflow.api.entity.Issue;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyWorkServiceImplTest {

    private static final String SLUG = "mi-equipo";

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

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null, null, "http://localhost:5173", "America/Bogota", null);
        myWorkService = new MyWorkServiceImpl(issueRepository, projectAccess, lookup, issueMapper, properties);
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
    }

    private void accessibleProjects(List<UUID> ids) {
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        when(projectAccess.projectIds(workspace, userId)).thenReturn(ids);
    }

    private static Issue issue(int sequenceId, LocalDate dueDate, IssuePriority priority) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setSequenceId(sequenceId);
        issue.setDueDate(dueDate);
        issue.setPriority(priority);
        return issue;
    }

    private void counts() {
        when(issueRepository.countAssignedTo(projectIds, userId, StateGroup.CLOSED)).thenReturn(7L);
        when(issueRepository.countCreatedBy(projectIds, userId, StateGroup.CLOSED)).thenReturn(4L);
        when(issueRepository.countCommentedBy(projectIds, userId, StateGroup.CLOSED)).thenReturn(3L);
        when(issueRepository.countAssignedCompletedSince(eq(projectIds), eq(userId), any(Instant.class)))
                .thenReturn(2L);
        when(issueRepository.countAssignedInGroup(projectIds, userId, StateGroup.STARTED)).thenReturn(1L);
    }

    @Test
    void vistaAsignadasConLosContadoresDeLasTresVistas() {
        accessibleProjects(projectIds);
        counts();
        Issue mine = issue(1, null, IssuePriority.NONE);
        when(issueRepository.findAssignedTo(projectIds, userId, StateGroup.CLOSED)).thenReturn(List.of(mine));

        MyWorkResponse response = myWorkService.getMyWork(SLUG, userId, MyWorkView.ASSIGNED);

        assertThat(response.issues()).hasSize(1);
        assertThat(response.assignedCount()).isEqualTo(7);
        assertThat(response.createdCount()).isEqualTo(4);
        assertThat(response.commentedCount()).isEqualTo(3);
        assertThat(response.completedThisWeek()).isEqualTo(2);
        assertThat(response.inProgressCount()).isEqualTo(1);
        verify(issueRepository, never()).findCreatedBy(any(), any(), any());
        verify(issueRepository, never()).findCommentedBy(any(), any(), any());
    }

    @Test
    void vistaCreadas() {
        accessibleProjects(projectIds);
        counts();
        when(issueRepository.findCreatedBy(projectIds, userId, StateGroup.CLOSED)).thenReturn(List.of());

        myWorkService.getMyWork(SLUG, userId, MyWorkView.CREATED);

        verify(issueRepository).findCreatedBy(projectIds, userId, StateGroup.CLOSED);
        verify(issueRepository, never()).findAssignedTo(any(), any(), any());
        verify(issueRepository, never()).findCommentedBy(any(), any(), any());
    }

    @Test
    void vistaComentadas() {
        accessibleProjects(projectIds);
        counts();
        when(issueRepository.findCommentedBy(projectIds, userId, StateGroup.CLOSED)).thenReturn(List.of());

        myWorkService.getMyWork(SLUG, userId, MyWorkView.COMMENTED);

        verify(issueRepository).findCommentedBy(projectIds, userId, StateGroup.CLOSED);
        verify(issueRepository, never()).findAssignedTo(any(), any(), any());
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
        when(issueRepository.findAssignedTo(projectIds, userId, StateGroup.CLOSED)).thenReturn(
                new ArrayList<>(List.of(noDueNone, laterLow, soonUrgent, noDueUrgent, soonLow, soonUrgentFirst)));

        myWorkService.getMyWork(SLUG, userId, MyWorkView.ASSIGNED);

        ArgumentCaptor<Issue> mapped = ArgumentCaptor.forClass(Issue.class);
        verify(issueMapper, times(6)).toResponse(mapped.capture());
        assertThat(mapped.getAllValues()).containsExactly(
                soonUrgentFirst, soonUrgent, soonLow, laterLow, noDueUrgent, noDueNone);
    }

    @Test
    void sinProyectosAccesiblesNoConsultaTareas() {
        accessibleProjects(List.of());

        MyWorkResponse response = myWorkService.getMyWork(SLUG, userId, MyWorkView.ASSIGNED);

        assertThat(response.issues()).isEmpty();
        assertThat(response.assignedCount()).isZero();
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
