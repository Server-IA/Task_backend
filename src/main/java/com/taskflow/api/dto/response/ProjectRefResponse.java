package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ProjectColor;

import java.util.UUID;

/**
 * Referencia corta a un proyecto (por ejemplo, en el feed de actividad), con su color para pintar la pastilla.
 */
public record ProjectRefResponse(
        UUID id,
        String name,
        String identifier,
        ProjectColor color
) {
}
