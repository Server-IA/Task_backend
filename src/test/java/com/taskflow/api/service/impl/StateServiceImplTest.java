package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.ReorderStatesRequest;
import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.exception.BadRequestException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        changeGroup(qa, StateGroup.COMPLETED);

        // Las historias del estado quedan cerradas por el project manager que cambió el grupo
        verify(issueRepository).closeStoriesInState(eq(qa.getId()), any(), eq(pm));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(stateRepository, storyProgress, relationService);
        order.verify(stateRepository).saveAndFlush(qa);
        // Las historias del estado y las de sus tareas se ponen al día en bloque (pueden quedar listas para revisar)
        order.verify(storyProgress).stateGroupChanged(qa, StateGroup.STARTED, pm);
        // Las tareas que se completaron dejan de bloquear a otras: un aviso por tarea, con una consulta
        order.verify(relationService).notifyUnblockedByState(qa, pm);
        verify(relationService, never()).notifyUnblocked(any(), any());
    }

    @Test
    void reordenarDejaCadaEstadoEnLaPosicionDeSuIndice() {
        State backlog = new State(project, "Backlog", "#000000", StateGroup.BACKLOG, 0);
        backlog.setId(UUID.randomUUID());
        State todo = new State(project, "Todo", "#000000", StateGroup.UNSTARTED, 1);
        todo.setId(UUID.randomUUID());
        State done = new State(project, "Done", "#000000", StateGroup.COMPLETED, 2);
        done.setId(UUID.randomUUID());
        when(stateRepository.findAllByProjectIdForUpdate(project.getId())).thenReturn(List.of(backlog, todo, done));
        when(projectMapper.toStateResponse(any())).thenAnswer(invocation -> {
            State state = invocation.getArgument(0);
            return new StateResponse(state.getId(), state.getName(), state.getColor(), state.getStateGroup(),
                    state.getPosition());
        });

        List<StateResponse> result = stateService.reorder(SLUG, project.getId(),
                new ReorderStatesRequest(List.of(done.getId(), backlog.getId(), todo.getId())));

        assertThat(done.getPosition()).isZero();
        assertThat(backlog.getPosition()).isEqualTo(1);
        assertThat(todo.getPosition()).isEqualTo(2);
        assertThat(result).extracting(StateResponse::id).containsExactly(done.getId(), backlog.getId(), todo.getId());
        verify(stateRepository).flush();
    }

    @Test
    void reordenarExigeExactamenteLosEstadosDelProyecto() {
        State backlog = new State(project, "Backlog", "#000000", StateGroup.BACKLOG, 0);
        backlog.setId(UUID.randomUUID());
        State todo = new State(project, "Todo", "#000000", StateGroup.UNSTARTED, 1);
        todo.setId(UUID.randomUUID());
        when(stateRepository.findAllByProjectIdForUpdate(project.getId())).thenReturn(List.of(backlog, todo));

        // Falta uno
        assertThatThrownBy(() -> stateService.reorder(SLUG, project.getId(),
                new ReorderStatesRequest(List.of(backlog.getId()))))
                .isInstanceOf(BadRequestException.class);
        // Sobra uno (de otro proyecto o inventado)
        assertThatThrownBy(() -> stateService.reorder(SLUG, project.getId(),
                new ReorderStatesRequest(List.of(backlog.getId(), todo.getId(), UUID.randomUUID()))))
                .isInstanceOf(BadRequestException.class);
        // Repetido
        assertThatThrownBy(() -> stateService.reorder(SLUG, project.getId(),
                new ReorderStatesRequest(List.of(backlog.getId(), todo.getId(), todo.getId()))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("repetir");
        // Nada cambió
        assertThat(backlog.getPosition()).isZero();
        assertThat(todo.getPosition()).isEqualTo(1);
        verify(stateRepository, never()).flush();
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
