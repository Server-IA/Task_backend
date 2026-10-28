package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Qué proyectos de un workspace puede ver un usuario: todos si es project manager; si no, solo aquellos
 * de los que es miembro. Lo usan los listados que cruzan proyectos (mi trabajo, actividad, búsqueda).
 */
@Component
@RequiredArgsConstructor
class ProjectAccess {

    private final ProjectRepository projectRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    List<Project> projects(Workspace workspace, UUID userId) {
        return isManager(workspace, userId)
                ? projectRepository.findAllByWorkspaceIdOrderByName(workspace.getId())
                : projectRepository.findAllByWorkspaceIdAndMember(workspace.getId(), userId);
    }

    List<UUID> projectIds(Workspace workspace, UUID userId) {
        return isManager(workspace, userId)
                ? projectRepository.findIdsByWorkspaceId(workspace.getId())
                : projectRepository.findIdsByWorkspaceIdAndMember(workspace.getId(), userId);
    }

    boolean isManager(Workspace workspace, UUID userId) {
        return workspaceMemberRepository.findRole(workspace.getSlug(), userId)
                .map(WorkspaceRole::isManager)
                .orElse(false);
    }
}
