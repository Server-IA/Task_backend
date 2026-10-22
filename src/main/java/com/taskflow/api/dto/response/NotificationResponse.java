package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        String message,
        boolean read,
        UserSummaryResponse actor,
        NotificationIssueResponse issue,
        Instant createdAt
) {
}
