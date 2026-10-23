package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Referencia corta a un ciclo o módulo dentro de un issue.
 */
public record PlanningRefResponse(
        UUID id,
        String name
) {
}
