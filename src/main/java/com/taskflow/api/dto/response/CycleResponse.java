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
        /** Cuándo se cerró; {@code null} mientras siga abierto. Un ciclo cerrado siempre está COMPLETED. */
        Instant closedAt,
        /** Quién lo cerró («cerrado por …»); {@code null} si sigue abierto o si esa persona ya no existe. */
        UserSummaryResponse closedBy,
        ProgressResponse progress,
        Instant createdAt,
        Instant updatedAt
) {
}
