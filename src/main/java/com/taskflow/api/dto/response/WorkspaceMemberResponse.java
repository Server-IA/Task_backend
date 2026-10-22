package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.WorkspaceRole;

import java.time.Instant;

public record WorkspaceMemberResponse(
        UserSummaryResponse user,
        WorkspaceRole role,
        Instant joinedAt
) {
}
