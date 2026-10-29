package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ProjectColor;

import java.util.UUID;

/**
 * Lo necesario para que el frontend navegue a la retrospectiva del ciclo de la notificación.
 */
public record NotificationCycleResponse(
        UUID id,
        String name,
        UUID projectId,
        String projectIdentifier,
        /** Color del proyecto del ciclo (clave de la paleta). */
        ProjectColor projectColor,
        String workspaceSlug
) {
}
