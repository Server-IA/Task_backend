package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ProjectColor;

import java.time.Instant;
import java.util.List;
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
        /** Miembros del proyecto (sin contar a los project managers del workspace que no son miembros). */
        int memberCount,
        /** Los primeros miembros por nombre (como mucho 5), para los avatares de la tarjeta. */
        List<UserSummaryResponse> members,
        Instant createdAt,
        Instant updatedAt
) {
}
