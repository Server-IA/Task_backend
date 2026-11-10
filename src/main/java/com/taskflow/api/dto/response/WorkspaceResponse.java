package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.WorkspaceRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un workspace y lo que quien lo pide puede hacer en él.
 *
 * @param myRole        rol base: {@code OWNER} (project manager) o {@code MEMBER}
 * @param myRoleId      id del rol creado por el workspace que tengo; {@code null} con un rol de fábrica (project
 *                      manager o «Miembro»)
 * @param myRoleName    nombre de mi rol («Project manager», «Miembro», «Líder»…)
 * @param myPermissions mis permisos (nombres de {@code Permission}); el project manager los tiene todos
 */
public record WorkspaceResponse(
        UUID id,
        String name,
        String slug,
        UserSummaryResponse owner,
        WorkspaceRole myRole,
        UUID myRoleId,
        String myRoleName,
        List<String> myPermissions,
        Instant createdAt
) {
}
