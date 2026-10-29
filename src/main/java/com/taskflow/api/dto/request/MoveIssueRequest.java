package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Mover una tarjeta en el Kanban. Se indican las tarjetas vecinas en la columna destino:
 * {@code afterIssueId} es la que queda justo arriba y {@code beforeIssueId} la que queda justo abajo.
 * Ambas son opcionales: sin ninguna, la tarjeta va al final de la columna.
 * <p>
 * {@code reopenParent}: mover una tarea ({@code TASK}) de una historia cerrada de un estado completado o cancelado a
 * uno abierto exige reabrir la historia. Con {@code true}, el project manager la reabre en la misma operación; sin él,
 * 409 con {@code code: STORY_CLOSED} (y un miembro recibe 403 aunque lo mande).
 */
public record MoveIssueRequest(
        @NotNull(message = "El estado destino es obligatorio")
        UUID stateId,

        UUID afterIssueId,

        UUID beforeIssueId,

        Boolean reopenParent
) {

    public MoveIssueRequest(UUID stateId, UUID afterIssueId, UUID beforeIssueId) {
        this(stateId, afterIssueId, beforeIssueId, null);
    }
}
