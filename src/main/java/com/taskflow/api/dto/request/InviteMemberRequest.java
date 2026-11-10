package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Invitación al workspace. La persona invitada entra con el rol «Miembro» o, con {@code roleId}, con uno de los roles
 * creados por el workspace; nunca como project manager (ese rol se asigna después, en Miembros).
 */
public record InviteMemberRequest(
        @NotBlank(message = "El correo es obligatorio")
        @Size(max = 255, message = "El correo es demasiado largo")
        @Email(message = "El correo no es válido")
        String email,

        UUID roleId
) {

    /** Sin los espacios de alrededor antes de validar: « ana@test.com » es un correo válido. */
    public InviteMemberRequest {
        email = email == null ? null : email.strip();
    }

    public InviteMemberRequest(String email) {
        this(email, null);
    }
}
