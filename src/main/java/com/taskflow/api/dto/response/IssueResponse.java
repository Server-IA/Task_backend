package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.IssuePriority;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IssueResponse(
        UUID id,
        String code,
        int sequenceId,
        UUID projectId,
        String title,
        String description,
        StateResponse state,
        IssuePriority priority,
        IssueSummaryResponse parent,
        LocalDate startDate,
        LocalDate dueDate,
        double sortOrder,
        List<UserSummaryResponse> assignees,
        List<LabelResponse> labels,
        PlanningRefResponse cycle,
        List<PlanningRefResponse> modules,
        int subIssueCount,
        int completedSubIssueCount,
        /** Tareas que la bloquean y siguen abiertas; mayor que 0 = «bloqueada». */
        int blockedByCount,
        /** Pasos de la checklist (total y hechos). */
        int checklistTotal,
        int checklistDone,
        /** Criterios de aceptación (total y cumplidos). */
        int criteriaTotal,
        int criteriaDone,
        UserSummaryResponse createdBy,
        Instant createdAt,
        Instant updatedAt,
        /** Cuándo se completó (entró a un estado del grupo COMPLETED); {@code null} si no está completada. */
        Instant completedAt
) {
}
