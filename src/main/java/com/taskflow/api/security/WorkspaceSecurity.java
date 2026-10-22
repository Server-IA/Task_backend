package com.taskflow.api.security;

import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Reglas de acceso por workspace, usadas desde {@code @PreAuthorize}, por ejemplo
 * {@code @PreAuthorize("@workspaceSecurity.isAdmin(#slug)")}. El rol se consulta en la base de datos.
 */
@Component("workspaceSecurity")
@RequiredArgsConstructor
public class WorkspaceSecurity {

    private final WorkspaceMemberRepository memberRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public boolean isMember(String slug) {
        return currentRole(slug).isPresent();
    }

    public boolean isAdmin(String slug) {
        return currentRole(slug).map(WorkspaceRole::isAdmin).orElse(false);
    }

    public boolean isOwner(String slug) {
        return currentRole(slug).map(role -> role == WorkspaceRole.OWNER).orElse(false);
    }

    /**
     * Un proyecto es accesible para los admins del workspace y para sus miembros.
     * Que el proyecto pertenezca al workspace lo valida el servicio (responde 404 si no).
     */
    public boolean canAccessProject(String slug, UUID projectId) {
        Optional<UUID> userId = SecurityUtils.currentUserId();
        Optional<WorkspaceRole> role = currentRole(slug);
        if (userId.isEmpty() || role.isEmpty()) {
            return false;
        }
        return role.get().isAdmin() || projectMemberRepository.existsByProjectIdAndUserId(projectId, userId.get());
    }

    private Optional<WorkspaceRole> currentRole(String slug) {
        return SecurityUtils.currentUserId()
                .flatMap(userId -> memberRepository.findRole(slug, userId));
    }
}
