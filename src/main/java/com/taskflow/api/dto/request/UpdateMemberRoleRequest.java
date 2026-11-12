package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Nuevo rol de un miembro: {@code OWNER} (project manager) o {@code MEMBER}. Con {@code MEMBER}, {@code roleId} elige
 * uno de los roles creados por el workspace; sin él queda con el rol «Miembro».
 */
public record UpdateMemberRoleRequest(
        @NotNull(message = "El rol es obligatorio (OWNER o MEMBER)")
        WorkspaceRole role,

        UUID roleId
) {

    public UpdateMemberRoleRequest(WorkspaceRole role) {
        this(role, null);
    }
}
