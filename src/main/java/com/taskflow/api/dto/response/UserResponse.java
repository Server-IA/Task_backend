package com.taskflow.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        String avatarUrl,
        boolean emailVerified,
        Instant createdAt
) {
}
