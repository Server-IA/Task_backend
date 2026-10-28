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
 * {@code @PreAuthorize("@workspaceSecurity.isManager(#slug)")}. El rol se consulta en la base de datos.
 * <ul>
 *   <li>Project manager ({@code OWNER}): todo, en todos los proyectos del workspace.</li>
 *   <li>Miembro ({@code MEMBER}): solo los proyectos de los que es miembro, y dentro de ellos trabaja
 *       con las tareas, pero no configura el proyecto (estados, etiquetas, miembros, ciclos, módulos).</li>
 * </ul>
 */
@Component("workspaceSecurity")
@RequiredArgsConstructor
public class WorkspaceSecurity {

    private final WorkspaceMemberRepository memberRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public boolean isMember(String slug) {
        return currentRole(slug).isPresent();
    }

    /** Project manager del workspace (rol {@code OWNER}). */
    public boolean isManager(String slug) {
        return currentRole(slug).map(WorkspaceRole::isManager).orElse(false);
    }

    /**
     * Un proyecto es accesible para los project managers del workspace y para sus miembros.
     * Que el proyecto pertenezca al workspace lo valida el servicio (responde 404 si no).
     */
    public boolean canAccessProject(String slug, UUID projectId) {
        Optional<UUID> userId = SecurityUtils.currentUserId();
        Optional<WorkspaceRole> role = currentRole(slug);
        if (userId.isEmpty() || role.isEmpty()) {
            return false;
        }
        return role.get().isManager() || projectMemberRepository.existsByProjectIdAndUserId(projectId, userId.get());
    }

    /**
     * Configurar un proyecto (editarlo, borrarlo, sus miembros, estados, etiquetas, ciclos y módulos)
     * es solo del project manager. Que el proyecto pertenezca al workspace lo valida el servicio.
     */
    public boolean canManageProject(String slug, UUID projectId) {
        return isManager(slug);
    }

    private Optional<WorkspaceRole> currentRole(String slug) {
        return SecurityUtils.currentUserId()
                .flatMap(userId -> memberRepository.findRole(slug, userId));
    }
}
