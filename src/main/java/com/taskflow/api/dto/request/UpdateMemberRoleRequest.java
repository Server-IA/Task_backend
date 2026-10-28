package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

/**
 * Nuevo rol de un miembro: {@code OWNER} (project manager) o {@code MEMBER}.
 */
public record UpdateMemberRoleRequest(
        @NotNull(message = "El rol es obligatorio (OWNER o MEMBER)")
        WorkspaceRole role
) {
}
