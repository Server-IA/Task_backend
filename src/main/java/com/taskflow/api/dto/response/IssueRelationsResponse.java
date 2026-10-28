package com.taskflow.api.dto.response;

import java.util.List;

/**
 * Dependencias de una tarea, cada lista ordenada por número de tarea.
 *
 * @param blocks    tareas que esta bloquea
 * @param blockedBy tareas que bloquean a esta
 * @param related   tareas relacionadas
 */
public record IssueRelationsResponse(
        List<IssueRelationResponse> blocks,
        List<IssueRelationResponse> blockedBy,
        List<IssueRelationResponse> related
) {
}
