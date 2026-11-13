package com.taskflow.api.security;

import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Reglas de acceso por workspace, usadas desde {@code @PreAuthorize}, por ejemplo
 * {@code @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'CYCLE_CREATE')")}. El rol y sus permisos
 * se consultan en la base de datos ({@link AccessControl}).
 * <ul>
 *   <li>{@link #isMember}: cualquier persona del workspace.</li>
 *   <li>{@link #isManager}: solo el project manager (eliminar el workspace).</li>
 *   <li>{@link #can}: un permiso del workspace (invitar, gestionar roles, crear proyectos…).</li>
 *   <li>{@link #canAccessProject}: ver un proyecto: ser miembro de él o tener «ver todos los proyectos».</li>
 *   <li>{@link #canInProject}: un permiso dentro de un proyecto que además se puede ver.</li>
 * </ul>
 */
@Component("workspaceSecurity")
@RequiredArgsConstructor
public class WorkspaceSecurity {

    private final AccessControl accessControl;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;

    public boolean isMember(String slug) {
        return currentAccess(slug).isPresent();
    }

    /** Project manager del workspace (rol {@code OWNER}). */
    public boolean isManager(String slug) {
        return currentAccess(slug).map(AccessControl.Access::isManager).orElse(false);
    }

    /** La persona tiene ese permiso (nombre de {@link Permission}) en el workspace. */
    public boolean can(String slug, String permission) {
        Permission required = Permission.valueOf(permission);
        return currentAccess(slug).map(access -> access.can(required)).orElse(false);
    }

    /**
     * Un proyecto es accesible para sus miembros y para quien tiene «ver todos los proyectos» (el project manager
     * siempre). Un proyecto de otro workspace nunca lo es (aunque el usuario sea miembro de él o project manager del de
     * la URL); si el proyecto no existe, el servicio responde 404.
     */
    public boolean canAccessProject(String slug, UUID projectId) {
        return currentAccess(slug).map(access -> canAccess(access, slug, projectId)).orElse(false);
    }

    /**
     * Hacer algo dentro de un proyecto (configurarlo, crear un ciclo, editar una tarea…): tener el permiso y poder ver
     * el proyecto.
     */
    public boolean canInProject(String slug, UUID projectId, String permission) {
        Permission required = Permission.valueOf(permission);
        return currentAccess(slug)
                .map(access -> access.can(required) && canAccess(access, slug, projectId))
                .orElse(false);
    }

    /** {@link #isMember} para otra persona (los avisos en tiempo real se reparten fuera de su petición). */
    public boolean isMember(UUID userId, String slug) {
        return accessControl.find(slug, userId).isPresent();
    }

    /** {@link #canAccessProject} para otra persona (los avisos en tiempo real se reparten fuera de su petición). */
    public boolean canAccessProject(UUID userId, String slug, UUID projectId) {
        return accessControl.find(slug, userId).map(access -> canAccess(access, userId, slug, projectId)).orElse(false);
    }

    private boolean canAccess(AccessControl.Access access, String slug, UUID projectId) {
        return SecurityUtils.currentUserId().map(userId -> canAccess(access, userId, slug, projectId)).orElse(false);
    }

    private boolean canAccess(AccessControl.Access access, UUID userId, String slug, UUID projectId) {
        if (access.can(Permission.PROJECT_VIEW_ALL)) {
            return !projectRepository.existsInOtherWorkspace(projectId, slug);
        }
        return projectMemberRepository.existsMemberInWorkspace(projectId, slug, userId);
    }

    private Optional<AccessControl.Access> currentAccess(String slug) {
        return SecurityUtils.currentUserId().flatMap(userId -> accessControl.find(slug, userId));
    }
}
