package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InviteMemberRequest(
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no es válido")
        String email,

        @NotNull(message = "El rol es obligatorio (ADMIN o MEMBER)")
        WorkspaceRole role
) {
}
