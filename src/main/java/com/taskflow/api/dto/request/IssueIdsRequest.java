package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record IssueIdsRequest(
        @NotEmpty(message = "Debes indicar al menos un issue")
        @Size(max = 100, message = "Máximo 100 issues por petición")
        List<@NotNull(message = "Id de issue inválido") UUID> issueIds
) {
}
