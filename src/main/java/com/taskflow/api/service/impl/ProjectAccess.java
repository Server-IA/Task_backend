package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.security.AccessControl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Qué proyectos de un workspace puede ver un usuario: todos si tiene «ver todos los proyectos» (el project manager
 * siempre); si no, solo aquellos de los que es miembro. Lo usan los listados que cruzan proyectos (mi trabajo,
 * actividad, búsqueda) y las dependencias entre tareas de distintos proyectos. También responde por los permisos de
 * la persona en el workspace de un proyecto.
 */
@Component
@RequiredArgsConstructor
class ProjectAccess {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final AccessControl accessControl;

    List<Project> projects(Workspace workspace, UUID userId) {
        return seesAllProjects(workspace, userId)
                ? projectRepository.findAllByWorkspaceIdOrderByName(workspace.getId())
                : projectRepository.findAllByWorkspaceIdAndMember(workspace.getId(), userId);
    }

    List<UUID> projectIds(Workspace workspace, UUID userId) {
        return seesAllProjects(workspace, userId)
                ? projectRepository.findIdsByWorkspaceId(workspace.getId())
                : projectRepository.findIdsByWorkspaceIdAndMember(workspace.getId(), userId);
    }

    /** El mismo criterio que {@code WorkspaceSecurity.canAccessProject}: ve todos los proyectos o es miembro de este. */
    boolean canAccess(Project project, UUID userId) {
        return seesAllProjects(project.getWorkspace(), userId)
                || projectMemberRepository.existsByProjectIdAndUserId(project.getId(), userId);
    }

    boolean seesAllProjects(Workspace workspace, UUID userId) {
        return can(workspace, userId, Permission.PROJECT_VIEW_ALL);
    }

    /** Si la persona tiene ese permiso en el workspace. */
    boolean can(Workspace workspace, UUID userId, Permission permission) {
        return accessControl.can(workspace.getSlug(), userId, permission);
    }
}
