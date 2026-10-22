package com.taskflow.api.security;

import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reglas de acceso por workspace, usadas desde {@code @PreAuthorize}, por ejemplo
 * {@code @PreAuthorize("@workspaceSecurity.isAdmin(#slug)")}. El rol se consulta en la base de datos.
 */
@Component("workspaceSecurity")
@RequiredArgsConstructor
public class WorkspaceSecurity {

    private final WorkspaceMemberRepository memberRepository;

    public boolean isMember(String slug) {
        return currentRole(slug).isPresent();
    }

    public boolean isAdmin(String slug) {
        return currentRole(slug).map(WorkspaceRole::isAdmin).orElse(false);
    }

    public boolean isOwner(String slug) {
        return currentRole(slug).map(role -> role == WorkspaceRole.OWNER).orElse(false);
    }

    private Optional<WorkspaceRole> currentRole(String slug) {
        return SecurityUtils.currentUserId()
                .flatMap(userId -> memberRepository.findRole(slug, userId));
    }
}
