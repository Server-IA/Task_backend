package com.taskflow.api.dto.response;

import java.time.Instant;

public record ProjectMemberResponse(
        UserSummaryResponse user,
        Instant joinedAt
) {
}
