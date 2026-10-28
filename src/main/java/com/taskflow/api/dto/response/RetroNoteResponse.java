package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.RetroColumn;

import java.time.Instant;
import java.util.UUID;

/**
 * Nota de la retrospectiva.
 *
 * @param votes     votos que recibió
 * @param votedByMe el usuario que pide ya votó por ella
 * @param issue     tarea creada a partir de la acción; {@code null} si no se convirtió
 */
public record RetroNoteResponse(
        UUID id,
        RetroColumn column,
        String text,
        UserSummaryResponse author,
        Instant createdAt,
        int votes,
        boolean votedByMe,
        RelatedIssueResponse issue
) {
}
