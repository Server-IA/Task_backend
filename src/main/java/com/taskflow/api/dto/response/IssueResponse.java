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
        UserSummaryResponse createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
