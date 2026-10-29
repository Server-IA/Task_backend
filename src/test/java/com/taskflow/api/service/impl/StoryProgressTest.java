package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.StoryChildCount;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El avance automático de las historias: empieza sola con su primera tarea, avisa una vez cuando queda lista para
 * revisar y pierde la marca si se reabre una tarea o llega una nueva o un bug abierto.
 */
@ExtendWith(MockitoExtension.class)
class StoryProgressTest {

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private StateRepository stateRepository;
    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock
    private ActivityService activityService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private StoryProgress progress;

    private Project project;
    private State todo;
    private State inProgress;
    private User luis;
    private User ana;
    private Issue story;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        project.setWorkspace(workspace);
        todo = state("Todo", StateGroup.UNSTARTED);
        inProgress = state("In Progress", StateGroup.STARTED);
        luis = user("Luis Muñoz Pérez");
        ana = user("Ana Navarro");
        story = issue(IssueType.STORY, todo, 15);
        lenient().when(stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(),
                StateGroup.STARTED)).thenReturn(Optional.of(inProgress));
        lenient().when(workspaceMemberRepository.findUsersWithRole(workspace.getId(), WorkspaceRole.OWNER))
                .thenReturn(List.of(ana));
    }

    private State state(String name, StateGroup group) {
        State state = new State(project, name, "#000000", group, 0);
        state.setId(UUID.randomUUID());
        return state;
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
    }

    private Issue issue(IssueType type, State state, int number) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setType(type);
        issue.setState(state);
        issue.setSequenceId(number);
        issue.setTitle("Tarea " + number);
        return issue;
    }

    private Issue child(IssueType type, State state, int number) {
        Issue child = issue(type, state, number);
        child.setParent(story);
        return child;
    }

    private void children(StoryChildCount... counts) {
        when(issueRepository.countChildrenOf(story.getId())).thenReturn(List.of(counts));
    }

    private static StoryChildCount count(IssueType type, StateGroup group, long count) {
        return new StoryChildCount(type, group, count);
    }

    @Nested
    class EmpiezaSola {

        @Test
        void laPrimeraTareaEnCursoMueveLaHistoriaYTaskFlowLoAnota() {
            Issue task = child(IssueType.TASK, inProgress, 41);
            children(count(IssueType.TASK, StateGroup.STARTED, 1), count(IssueType.TASK, StateGroup.UNSTARTED, 4));

            progress.childChanged(task, null, luis, true);

            assertThat(story.getState()).isSameAs(inProgress);
            assertThat(story.getStartedAt()).isNotNull();
            verify(activityService).record(story, null, ActivityField.STORY_AUTO_STATE, "Luis empezó WEB-41",
                    "In Progress");
        }

        @Test
        void unaHistoriaYaEnCursoNoSeMueveDeNuevo() {
            story.setState(inProgress);
            Issue task = child(IssueType.TASK, inProgress, 42);
            children(count(IssueType.TASK, StateGroup.STARTED, 2));

            progress.childChanged(task, null, luis, true);

            verify(activityService, never()).record(any(), any(), any(), any(), any());
        }

        @Test
        void unBugEnCursoNoMueveLaHistoria() {
            Issue bug = child(IssueType.BUG, inProgress, 46);
            children(count(IssueType.BUG, StateGroup.STARTED, 1));

            progress.childChanged(bug, null, luis, true);

            assertThat(story.getState()).isSameAs(todo);
        }

        @Test
        void unaTareaQueNoEmpezoNoLaMueve() {
            Issue task = child(IssueType.TASK, todo, 43);
            children(count(IssueType.TASK, StateGroup.UNSTARTED, 1));

            progress.childChanged(task, null, luis, false);

            assertThat(story.getState()).isSameAs(todo);
        }

        @Test
        void unaTareaSinHistoriaNoHaceNada() {
            Issue task = issue(IssueType.TASK, inProgress, 12);

            progress.childChanged(task, null, luis, true);

            verify(issueRepository, never()).countChildrenOf(any());
        }
    }

    @Nested
    class ListaParaRevisar {

        @BeforeEach
        void started() {
            story.setState(inProgress);
        }

        @Test
        void avisaUnaVezALosProjectManagersSinAutor() {
            Issue task = child(IssueType.TASK, inProgress, 45);
            children(count(IssueType.TASK, StateGroup.COMPLETED, 5));

            progress.childChanged(task, null, luis, false);
            progress.childChanged(task, null, luis, false);

            assertThat(story.isReviewNotified()).isTrue();
            assertThat(story.getState()).isSameAs(inProgress);
            verify(notificationService, times(1)).notifyStoryReady(eq(story), eq(List.of(ana)), eq(luis));
        }

        @Test
        void unBugLigadoAbiertoLaFrena() {
            Issue task = child(IssueType.TASK, inProgress, 45);
            children(count(IssueType.TASK, StateGroup.COMPLETED, 5), count(IssueType.BUG, StateGroup.STARTED, 1));

            progress.childChanged(task, null, luis, false);

            assertThat(story.isReviewNotified()).isFalse();
            verify(notificationService, never()).notifyStoryReady(any(), anyCollection(), any());
        }

        @Test
        void lasTareasCanceladasCuentanComoTerminadas() {
            Issue task = child(IssueType.TASK, inProgress, 45);
            children(count(IssueType.TASK, StateGroup.COMPLETED, 3), count(IssueType.TASK, StateGroup.CANCELLED, 1),
                    count(IssueType.BUG, StateGroup.COMPLETED, 1));

            progress.childChanged(task, null, luis, false);

            verify(notificationService).notifyStoryReady(eq(story), anyCollection(), eq(luis));
        }

        @Test
        void reabrirUnaTareaBorraLaMarcaYElSiguienteListaVuelveAAvisar() {
            Issue task = child(IssueType.TASK, inProgress, 45);
            story.setReviewNotified(true);
            when(issueRepository.countChildrenOf(story.getId()))
                    .thenReturn(List.of(count(IssueType.TASK, StateGroup.STARTED, 1),
                            count(IssueType.TASK, StateGroup.COMPLETED, 4)))
                    .thenReturn(List.of(count(IssueType.TASK, StateGroup.COMPLETED, 5)));

            progress.childChanged(task, null, luis, false);
            assertThat(story.isReviewNotified()).isFalse();

            progress.childChanged(task, null, luis, false);
            assertThat(story.isReviewNotified()).isTrue();
            verify(notificationService).notifyStoryReady(eq(story), anyCollection(), eq(luis));
        }

        @Test
        void unaTareaQueSeVaDeLaHistoriaTambienLaRevisa() {
            Issue task = issue(IssueType.TASK, inProgress, 45);
            when(issueRepository.countChildrenOf(story.getId()))
                    .thenReturn(List.of(count(IssueType.TASK, StateGroup.COMPLETED, 4)));

            progress.childChanged(task, story, luis, false);

            verify(notificationService).notifyStoryReady(eq(story), anyCollection(), eq(luis));
        }

        @Test
        void unaHistoriaCerradaNoQuedaListaParaRevisar() {
            story.setState(state("Done", StateGroup.COMPLETED));
            children(count(IssueType.TASK, StateGroup.COMPLETED, 5));

            progress.reconcile(story, ana, null, false);

            verify(notificationService, never()).notifyStoryReady(any(), anyCollection(), isNull());
            assertThat(story.isReviewNotified()).isFalse();
        }
    }
}
