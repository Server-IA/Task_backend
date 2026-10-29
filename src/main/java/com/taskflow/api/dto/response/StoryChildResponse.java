package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una tarea o un bug de una historia, para la fila desplegada de «Historias».
 *
 * @param afterClose un bug creado después de cerrar la historia («después de cerrar»)
 */
public record StoryChildResponse(
        UUID id,
        String code,
        String title,
        IssueType type,
        StateResponse state,
        IssuePriority priority,
        List<UserSummaryResponse> assignees,
        Instant createdAt,
        boolean afterClose
) {
}
