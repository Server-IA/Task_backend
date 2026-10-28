package com.taskflow.api.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Referencia a una tarea con lo necesario para mostrarla (código, estado y asignados): la otra tarea de una
 * dependencia, las tareas del cierre de un ciclo, la tarea creada desde una acción de la retrospectiva o las de
 * la daily.
 */
public record RelatedIssueResponse(
        UUID id,
        String code,
        String title,
        StateResponse state,
        List<UserSummaryResponse> assignees
) {
}
