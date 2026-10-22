package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Lo necesario para que el frontend navegue al issue de la notificación.
 */
public record NotificationIssueResponse(
        UUID id,
        String code,
        String title,
        UUID projectId,
        String workspaceSlug
) {
}
