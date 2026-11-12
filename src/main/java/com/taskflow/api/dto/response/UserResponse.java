package com.taskflow.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * @param language idioma que eligió la persona: «es» o «en»; {@code null} si nunca lo eligió (el frontend usa
 *                 entonces el del navegador)
 */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        String avatarUrl,
        boolean emailVerified,
        Instant createdAt,
        String language
) {
}
