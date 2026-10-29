package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Usado en reenviar verificación y olvidé mi contraseña.
 */
public record EmailRequest(
        @NotBlank(message = "El correo es obligatorio")
        @Size(max = 255, message = "El correo es demasiado largo")
        @Email(message = "El correo no es válido")
        String email
) {

    /** Sin los espacios de alrededor antes de validar: « ana@test.com » es un correo válido. */
    public EmailRequest {
        email = email == null ? null : email.strip();
    }
}
