package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ActivityField;

import java.time.Instant;
import java.util.UUID;

/**
 * Entrada del feed de actividad del workspace: el cambio y la tarea y el proyecto donde ocurrió.
 * En los comentarios ({@code field: COMMENT}), {@code newValue} trae el texto (recortado a 240 caracteres).
 */
public record ActivityFeedItemResponse(
        UUID id,
        UserSummaryResponse actor,
        ActivityField field,
        String oldValue,
        String newValue,
        Instant createdAt,
        IssueSummaryResponse issue,
        ProjectRefResponse project
) {
}
