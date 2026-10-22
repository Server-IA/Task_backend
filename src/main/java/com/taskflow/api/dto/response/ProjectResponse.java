package com.taskflow.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String identifier,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
}
