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
        /** Tareas y bugs (las historias no cuentan en issueCount, openIssueCount ni overdueIssueCount). */
        int issueCount,
        int openIssueCount,
        int overdueIssueCount,
        /** Historias abiertas y, de ellas, las «sin partir» (sin tareas): «Historias · 2 sin partir». */
        int openStoryCount,
        int unsplitStoryCount,
        /**
         * Código que recibirá la próxima tarea («→ será WEB-26»). Orientativo: si otra persona crea una tarea antes,
         * esa se lleva el número.
         */
        String nextIssueCode,
        Instant createdAt,
        Instant updatedAt
) {
}
