package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "La contraseña actual es obligatoria")
        @Size(max = 256, message = "La contraseña actual es demasiado larga")
        String currentPassword,

        @NotBlank(message = "La nueva contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        @Pattern(regexp = PasswordRules.REGEX, message = PasswordRules.MESSAGE)
        @MaxUtf8Bytes(value = PasswordRules.MAX_BYTES, message = PasswordRules.BYTES_MESSAGE)
        String newPassword
) {
}
