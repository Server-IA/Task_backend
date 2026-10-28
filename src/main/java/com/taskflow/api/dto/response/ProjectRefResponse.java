package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Referencia corta a un proyecto (por ejemplo, en el feed de actividad).
 */
public record ProjectRefResponse(
        UUID id,
        String name,
        String identifier
) {
}
