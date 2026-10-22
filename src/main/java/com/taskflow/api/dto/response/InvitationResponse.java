package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

public record InvitationResponse(
        UUID id,
        String email,
        WorkspaceRole role,
        UserSummaryResponse invitedBy,
        Instant expiresAt,
        Instant createdAt
) {
}
