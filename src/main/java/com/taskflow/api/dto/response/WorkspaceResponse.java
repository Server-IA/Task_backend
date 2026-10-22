package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(
        UUID id,
        String name,
        String slug,
        UserSummaryResponse owner,
        WorkspaceRole myRole,
        Instant createdAt
) {
}
