package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Mover una tarjeta en el Kanban. Se indican las tarjetas vecinas en la columna destino:
 * {@code afterIssueId} es la que queda justo arriba y {@code beforeIssueId} la que queda justo abajo.
 * Ambas son opcionales: sin ninguna, la tarjeta va al final de la columna.
 */
public record MoveIssueRequest(
        @NotNull(message = "El estado destino es obligatorio")
        UUID stateId,

        UUID afterIssueId,

        UUID beforeIssueId
) {
}
