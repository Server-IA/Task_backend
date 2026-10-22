package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.UpdateProjectRequest;
import com.taskflow.api.dto.response.ProjectMemberResponse;
import com.taskflow.api.dto.response.ProjectResponse;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final StateRepository stateRepository;
    private final ResourceLookup lookup;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjects(String slug, UUID userId) {
        Workspace workspace = lookup.workspace(slug);
        boolean isAdmin = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .map(WorkspaceMember::getRole)
                .map(WorkspaceRole::isAdmin)
                .orElse(false);
        List<Project> projects = isAdmin
                ? projectRepository.findAllByWorkspaceIdOrderByName(workspace.getId())
                : projectRepository.findAllByWorkspaceIdAndMember(workspace.getId(), userId);
        return projects.stream().map(projectMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public ProjectResponse create(String slug, UUID userId, CreateProjectRequest request) {
        Workspace workspace = lookup.workspace(slug);
        String identifier = request.identifier().toUpperCase(Locale.ROOT);
        if (projectRepository.existsByWorkspaceIdAndIdentifier(workspace.getId(), identifier)) {
            throw new ConflictException("Ya existe un proyecto con el identificador " + identifier);
        }

        Project project = new Project();
        project.setWorkspace(workspace);
        project.setName(request.name().trim());
        project.setIdentifier(identifier);
        project.setDescription(blankToNull(request.description()));
        projectRepository.save(project);

        stateRepository.saveAll(defaultStates(project));
        projectMemberRepository.save(new ProjectMember(project, lookup.user(userId)));
        return projectMapper.toResponse(project);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectResponse get(String slug, UUID projectId) {
        return projectMapper.toResponse(lookup.project(slug, projectId));
    }

    @Override
    @Transactional
    public ProjectResponse update(String slug, UUID projectId, UpdateProjectRequest request) {
        Project project = lookup.project(slug, projectId);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            project.setName(request.name().trim());
        }
        if (request.identifier() != null) {
            String identifier = request.identifier().toUpperCase(Locale.ROOT);
            if (!identifier.equals(project.getIdentifier())
                    && projectRepository.existsByWorkspaceIdAndIdentifier(project.getWorkspace().getId(), identifier)) {
                throw new ConflictException("Ya existe un proyecto con el identificador " + identifier);
            }
            // Los códigos de los issues se calculan al vuelo, así que cambian todos a la vez (WEB-1 → APP-1).
            project.setIdentifier(identifier);
        }
        if (request.description() != null) {
            project.setDescription(blankToNull(request.description()));
        }
        // El flush ejecuta @PreUpdate para que la respuesta lleve el updatedAt nuevo.
        return projectMapper.toResponse(projectRepository.saveAndFlush(project));
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId) {
        projectRepository.delete(lookup.project(slug, projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> getMembers(String slug, UUID projectId) {
        Project project = lookup.project(slug, projectId);
        return projectMemberRepository.findAllByProjectIdWithUser(project.getId()).stream()
                .map(projectMapper::toMemberResponse)
                .toList();
    }

    @Override
    @Transactional
    public ProjectMemberResponse addMember(String slug, UUID projectId, AddProjectMemberRequest request) {
        Project project = lookup.project(slug, projectId);
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserId(project.getWorkspace().getId(), request.userId())) {
            throw new BadRequestException("El usuario debe ser miembro del workspace");
        }
        if (projectMemberRepository.existsByProjectIdAndUserId(project.getId(), request.userId())) {
            throw new ConflictException("El usuario ya es miembro del proyecto");
        }
        User user = lookup.user(request.userId());
        ProjectMember member = projectMemberRepository.save(new ProjectMember(project, user));
        return projectMapper.toMemberResponse(member);
    }

    @Override
    @Transactional
    public void removeMember(String slug, UUID projectId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(project.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("El usuario no es miembro del proyecto"));
        projectMemberRepository.delete(member);
    }

    /** Backlog, Todo, In Progress, Done y Cancelled, como en Plane. */
    private static List<State> defaultStates(Project project) {
        return List.of(
                new State(project, "Backlog", "#A3A3A3", StateGroup.BACKLOG, 0),
                new State(project, "Todo", "#3A3A3A", StateGroup.UNSTARTED, 1),
                new State(project, "In Progress", "#F59E0B", StateGroup.STARTED, 2),
                new State(project, "Done", "#16A34A", StateGroup.COMPLETED, 3),
                new State(project, "Cancelled", "#DC2626", StateGroup.CANCELLED, 4));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
