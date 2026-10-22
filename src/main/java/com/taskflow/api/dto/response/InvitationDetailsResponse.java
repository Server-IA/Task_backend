package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.WorkspaceRole;

import java.time.Instant;

/**
 * Lo que ve el invitado antes de aceptar.
 */
public record InvitationDetailsResponse(
        String workspaceName,
        String workspaceSlug,
        String email,
        WorkspaceRole role,
        String invitedByName,
        Instant expiresAt
) {
}
