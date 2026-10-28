package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateStateRequest;
import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.service.StateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StateServiceImpl implements StateService {

    private final StateRepository stateRepository;
    private final IssueRepository issueRepository;
    private final ResourceLookup lookup;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<StateResponse> getStates(String slug, UUID projectId) {
        Project project = lookup.project(slug, projectId);
        return stateRepository.findAllByProjectIdOrderByPositionAsc(project.getId()).stream()
                .map(projectMapper::toStateResponse)
                .toList();
    }

    @Override
    @Transactional
    public StateResponse create(String slug, UUID projectId, CreateStateRequest request) {
        Project project = lookup.project(slug, projectId);
        String name = request.name().trim();
        ensureNameAvailable(project, name);

        int position = request.position() != null
                ? request.position()
                : stateRepository.findMaxPosition(project.getId()) + 1;
        State state = new State(project, name, request.color().toUpperCase(Locale.ROOT), request.group(), position);
        return projectMapper.toStateResponse(stateRepository.save(state));
    }

    @Override
    @Transactional
    public StateResponse update(String slug, UUID projectId, UUID stateId, UpdateStateRequest request) {
        Project project = lookup.project(slug, projectId);
        State state = findState(project, stateId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            if (!name.equalsIgnoreCase(state.getName())) {
                ensureNameAvailable(project, name);
            }
            state.setName(name);
        }
        if (request.color() != null) {
            state.setColor(request.color().toUpperCase(Locale.ROOT));
        }
        if (request.group() != null && request.group() != state.getStateGroup()) {
            // Las tareas del estado cambian de grupo con él: se mantiene su fecha de completado.
            if (request.group() == StateGroup.COMPLETED) {
                issueRepository.markCompletedInState(state.getId(), Instant.now());
            } else if (state.getStateGroup() == StateGroup.COMPLETED) {
                issueRepository.clearCompletedInState(state.getId());
            }
            // Y las que nunca habían empezado empiezan ahora si el estado pasa a STARTED.
            if (request.group() == StateGroup.STARTED) {
                issueRepository.markStartedInState(state.getId(), Instant.now());
            }
            state.setStateGroup(request.group());
        }
        if (request.position() != null) {
            state.setPosition(request.position());
        }
        return projectMapper.toStateResponse(state);
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID stateId) {
        Project project = lookup.project(slug, projectId);
        State state = findState(project, stateId);
        if (stateRepository.countByProjectId(project.getId()) <= 1) {
            throw new ConflictException("El proyecto debe tener al menos un estado");
        }
        if (issueRepository.existsByStateId(state.getId())) {
            throw new ConflictException("El estado tiene tareas; muévelas a otro estado antes de eliminarlo");
        }
        stateRepository.delete(state);
    }

    private State findState(Project project, UUID stateId) {
        return stateRepository.findByIdAndProjectId(stateId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Estado no encontrado"));
    }

    private void ensureNameAvailable(Project project, String name) {
        if (stateRepository.existsByProjectIdAndNameIgnoreCase(project.getId(), name)) {
            throw new ConflictException("Ya existe un estado llamado '" + name + "'");
        }
    }
}
