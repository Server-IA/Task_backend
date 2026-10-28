package com.taskflow.api.repository;

import com.taskflow.api.entity.enums.StateGroup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lo que necesitan las estadísticas de cada tarea, leído en una sola consulta sin cargar las entidades.
 */
public record IssueStatsRow(
        UUID id,
        int sequenceId,
        String title,
        UUID stateId,
        StateGroup group,
        LocalDate dueDate,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
