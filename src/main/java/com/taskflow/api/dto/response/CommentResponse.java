package com.taskflow.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UserSummaryResponse author,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
}
