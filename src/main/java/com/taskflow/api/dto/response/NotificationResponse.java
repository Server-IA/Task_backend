package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.NotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * @param issue   tarea de la notificación; {@code null} en {@code RETRO_STARTED}
 * @param cycle   ciclo de la notificación ({@code RETRO_STARTED}); {@code null} en las demás
 * @param comment comentario que la generó ({@code ISSUE_COMMENTED} e {@code ISSUE_MENTIONED}); {@code null} en las
 *                demás o si el comentario se borró
 */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        String message,
        boolean read,
        UserSummaryResponse actor,
        NotificationIssueResponse issue,
        NotificationCycleResponse cycle,
        NotificationCommentResponse comment,
        Instant createdAt
) {
}
