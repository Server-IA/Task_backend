package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(
        @NotNull(message = "El rol es obligatorio")
        WorkspaceRole role
) {
}
