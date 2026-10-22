package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Token recibido por correo (por ejemplo, el de verificación de email).
 */
public record TokenRequest(
        @NotBlank(message = "El token es obligatorio")
        String token
) {
}
