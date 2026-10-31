package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Workspace de una notificación que no es de una tarea ni de un ciclo ({@code STORAGE_WARNING}).
 */
public record NotificationWorkspaceResponse(
        UUID id,
        String slug,
        String name
) {
}
