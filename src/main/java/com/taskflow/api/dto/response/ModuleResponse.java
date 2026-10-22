package com.taskflow.api.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ModuleResponse(
        UUID id,
        String name,
        String description,
        LocalDate startDate,
        LocalDate targetDate,
        ProgressResponse progress,
        Instant createdAt,
        Instant updatedAt
) {
}
