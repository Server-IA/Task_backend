package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.WorkspaceRole;

import java.time.Instant;

/**
 * Lo que ve el invitado antes de aceptar.
 *
 * @param role     rol base con el que entra (siempre {@code MEMBER})
 * @param roleName nombre del rol con el que entra («Miembro» o el rol del workspace que eligió quien invita)
 */
public record InvitationDetailsResponse(
        String workspaceName,
        String workspaceSlug,
        String email,
        WorkspaceRole role,
        String roleName,
        String invitedByName,
        Instant expiresAt
) {
}
