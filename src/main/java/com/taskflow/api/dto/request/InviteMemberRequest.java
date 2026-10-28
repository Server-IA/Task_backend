package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Invitación al workspace. La persona invitada entra siempre como {@code MEMBER}; después el
 * project manager puede promoverla.
 */
public record InviteMemberRequest(
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no es válido")
        String email
) {
}
