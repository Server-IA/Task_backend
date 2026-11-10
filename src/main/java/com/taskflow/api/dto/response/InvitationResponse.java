package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Una invitación pendiente.
 *
 * @param role     rol base con el que entra (siempre {@code MEMBER})
 * @param roleId   id del rol del workspace con el que entra; {@code null}: «Miembro»
 * @param roleName nombre de ese rol
 */
public record InvitationResponse(
        UUID id,
        String email,
        WorkspaceRole role,
        UUID roleId,
        String roleName,
        UserSummaryResponse invitedBy,
        Instant expiresAt,
        Instant createdAt
) {
}
