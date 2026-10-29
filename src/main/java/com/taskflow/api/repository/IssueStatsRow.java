package com.taskflow.api.repository;

import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lo que necesitan las estadísticas de cada tarea, leído en una sola consulta sin cargar las entidades. Las
 * estadísticas cuentan el trabajo (tareas y bugs): las historias no entran.
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
        Instant startedAt,
        Instant completedAt,
        IssueType type
) {

    public IssueStatsRow(UUID id, int sequenceId, String title, UUID stateId, StateGroup group, LocalDate dueDate,
                         Instant createdAt, Instant updatedAt, Instant startedAt, Instant completedAt) {
        this(id, sequenceId, title, stateId, group, dueDate, createdAt, updatedAt, startedAt, completedAt,
                IssueType.TASK);
    }
}
