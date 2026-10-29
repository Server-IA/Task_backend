package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.ProjectColor;

import java.util.UUID;

/**
 * Lo necesario para que el frontend navegue al issue de la notificación.
 */
public record NotificationIssueResponse(
        UUID id,
        String code,
        String title,
        /** Historia, tarea o bug (para su icono en la bandeja). */
        IssueType type,
        UUID projectId,
        /** Color del proyecto de la tarea (clave de la paleta). */
        ProjectColor projectColor,
        String workspaceSlug
) {
}
