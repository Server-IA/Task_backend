package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.StateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    private StateServiceImpl stateService;

    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
    }

    private State state(StateGroup group) {
        State state = new State(project, "QA", "#000000", group, 0);
        state.setId(UUID.randomUUID());
        when(stateRepository.findByIdAndProjectId(state.getId(), project.getId())).thenReturn(Optional.of(state));
        return state;
    }

    private void changeGroup(State state, StateGroup group) {
        stateService.update(SLUG, project.getId(), state.getId(), new UpdateStateRequest(null, null, group, null));
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
    }

    @Test
    void cambiarEntreGruposAbiertosNoTocaLasTareas() {
        State qa = state(StateGroup.UNSTARTED);

        changeGroup(qa, StateGroup.STARTED);

        verifyNoInteractions(issueRepository);
    }
}
