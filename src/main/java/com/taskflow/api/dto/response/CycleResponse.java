package com.taskflow.api.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CycleResponse(
        UUID id,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        CycleStatus status,
        ProgressResponse progress,
        Instant createdAt,
        Instant updatedAt
) {
}
