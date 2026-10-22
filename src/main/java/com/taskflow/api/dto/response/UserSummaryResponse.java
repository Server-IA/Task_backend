package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Datos mínimos de un usuario para mostrarlo dentro de otros recursos (asignados, autor, etc.).
 */
public record UserSummaryResponse(
        UUID id,
        String email,
        String fullName,
        String avatarUrl
) {
}
