package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Los campos nulos no se modifican. Un avatarUrl vacío lo elimina.
 */
public record UpdateProfileRequest(
        @Size(max = 120, message = "El nombre no puede superar 120 caracteres")
        String fullName,

        @Size(max = 500, message = "La URL del avatar es demasiado larga")
        @Pattern(regexp = "^$|^https?://.+", message = "La URL del avatar debe empezar por http:// o https://")
        String avatarUrl
) {
}
