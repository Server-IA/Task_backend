package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no es válido")
        @Size(max = 255, message = "El correo es demasiado largo")
        String email,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        @Pattern(regexp = PasswordRules.REGEX, message = PasswordRules.MESSAGE)
        @MaxUtf8Bytes(value = PasswordRules.MAX_BYTES, message = PasswordRules.BYTES_MESSAGE)
        String password,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120, message = "El nombre no puede superar 120 caracteres")
        String fullName
) {

    /** Sin los espacios de alrededor antes de validar: « ana@test.com » es un correo válido. */
    public RegisterRequest {
        email = email == null ? null : email.strip();
    }
}
