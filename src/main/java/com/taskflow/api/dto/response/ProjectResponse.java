package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ProjectColor;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String identifier,
        /** Clave de la paleta; el frontend tiene sus tonos para el tema claro y el oscuro. */
        ProjectColor color,
        String description,
        int issueCount,
        int openIssueCount,
        int overdueIssueCount,
        /**
         * Código que recibirá la próxima tarea («→ será WEB-26»). Orientativo: si otra persona crea una tarea antes,
         * esa se lleva el número.
         */
        String nextIssueCode,
        Instant createdAt,
        Instant updatedAt
) {
}
