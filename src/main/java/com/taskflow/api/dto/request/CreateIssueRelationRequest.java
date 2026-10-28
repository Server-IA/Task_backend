package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Relaciona la tarea de la URL con otra tarea del mismo proyecto.
 */
public record CreateIssueRelationRequest(
        @NotNull(message = "El tipo de relación es obligatorio")
        IssueRelationKind type,

        @NotNull(message = "La tarea relacionada es obligatoria")
        UUID issueId
) {
}
