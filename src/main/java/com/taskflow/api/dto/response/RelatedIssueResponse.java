package com.taskflow.api.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * La otra tarea de una dependencia, con lo necesario para mostrarla (código, estado y asignados).
 */
public record RelatedIssueResponse(
        UUID id,
        String code,
        String title,
        StateResponse state,
        List<UserSummaryResponse> assignees
) {
}
