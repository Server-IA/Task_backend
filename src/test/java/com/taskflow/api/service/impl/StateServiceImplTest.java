package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.service.IssueRelationService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Si un estado cambia de grupo, sus tareas entran o salen del grupo COMPLETED con él.
 */
@ExtendWith(MockitoExtension.class)
class StateServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private StateRepository stateRepository;
    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private IssueRelationService relationService;
    @Mock
    private StoryProgress storyProgress;

    @InjectMocks
    private StateServiceImpl stateService;

    private Project project;
    private final User pm = new User();

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        pm.setId(UUID.randomUUID());
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(lookup.user(pm.getId())).thenReturn(pm);
    }

    private State state(StateGroup group) {
        State state = new State(project, "QA", "#000000", group, 0);
        state.setId(UUID.randomUUID());
        when(stateRepository.findByIdAndProjectId(state.getId(), project.getId())).thenReturn(Optional.of(state));
        return state;
    }

    private void changeGroup(State state, StateGroup group) {
        stateService.update(SLUG, project.getId(), state.getId(), pm.getId(),
                new UpdateStateRequest(null, null, group, null));
    }

    @Test
    void pasarAlGrupoCompletadoCompletaSusTareas() {
        State qa = state(StateGroup.STARTED);

        changeGroup(qa, StateGroup.COMPLETED);

        verify(issueRepository).markCompletedInState(eq(qa.getId()), any());
        assertThat(qa.getStateGroup()).isEqualTo(StateGroup.COMPLETED);
    }

    @Test
    void salirDelGrupoCompletadoBorraLaFechaDeSusTareas() {
        State done = state(StateGroup.COMPLETED);

        changeGroup(done, StateGroup.STARTED);

        verify(issueRepository).clearCompletedInState(done.getId());
        verify(issueRepository, never()).markCompletedInState(any(), any());
        // Vuelve a STARTED: sus tareas ya habían empezado; las que no, empiezan ahora
        verify(issueRepository).markStartedInState(eq(done.getId()), any());
    }

    @Test
    void pasarAlGrupoEnCursoEmpiezaLasTareasQueNuncaHabianEmpezado() {
        State qa = state(StateGroup.UNSTARTED);

        changeGroup(qa, StateGroup.STARTED);

        verify(issueRepository).markStartedInState(eq(qa.getId()), any());
        verify(issueRepository, never()).markCompletedInState(any(), any());
        verify(issueRepository, never()).clearCompletedInState(any());
    }

    @Test
    void cambiarEntreGruposSinEmpezarNoTocaLasTareas() {
        State qa = state(StateGroup.BACKLOG);

        changeGroup(qa, StateGroup.UNSTARTED);

        verify(issueRepository, never()).markCompletedInState(any(), any());
        verify(issueRepository, never()).clearCompletedInState(any());
        verify(issueRepository, never()).markStartedInState(any(), any());
        verify(issueRepository, never()).closeStoriesInState(any(), any(), any());
        verify(issueRepository, never()).reopenStoriesInState(any());
        verifyNoInteractions(relationService);
    }

    @Test
    void pasarAUnGrupoCerradoCierraSusHistoriasPonAlDiaLasDemasYAvisaDesbloqueos() {
        State qa = state(StateGroup.STARTED);
        Issue story = new Issue();
        story.setId(UUID.randomUUID());
        Issue task = new Issue();
        task.setId(UUID.randomUUID());
        when(issueRepository.findStoryIdsAffectedByState(qa.getId())).thenReturn(List.of(story.getId()));
        when(issueRepository.findById(story.getId())).thenReturn(Optional.of(story));
        when(issueRepository.findAllByStateId(qa.getId())).thenReturn(List.of(task));

        changeGroup(qa, StateGroup.COMPLETED);

        // Las historias del estado quedan cerradas por el project manager que cambió el grupo
        verify(issueRepository).closeStoriesInState(eq(qa.getId()), any(), eq(pm));
        verify(stateRepository).saveAndFlush(qa);
        // Las historias del estado y las de sus tareas se ponen al día (pueden quedar listas para revisar)
        verify(storyProgress).reconcile(story, pm, null, false);
        // Las tareas que se completaron dejan de bloquear a otras
        verify(relationService).notifyUnblocked(task, pm);
    }

    @Test
    void volverAUnGrupoAbiertoReabreSusHistorias() {
        State done = state(StateGroup.COMPLETED);

        changeGroup(done, StateGroup.UNSTARTED);

        verify(issueRepository).reopenStoriesInState(done.getId());
        verify(issueRepository, never()).closeStoriesInState(any(), any(), any());
        verifyNoInteractions(relationService);
    }

    @Test
    void entreCompletadoYCanceladoLasHistoriasSiguenCerradasConSuFecha() {
        State done = state(StateGroup.COMPLETED);

        changeGroup(done, StateGroup.CANCELLED);

        verify(issueRepository, never()).closeStoriesInState(any(), any(), any());
        verify(issueRepository, never()).reopenStoriesInState(any());
    }
}
