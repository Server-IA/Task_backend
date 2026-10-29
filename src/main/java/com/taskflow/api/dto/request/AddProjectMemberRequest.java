package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddProjectMemberRequest(
        @NotNull(message = "El usuario es obligatorio")
        UUID userId
) {
}
