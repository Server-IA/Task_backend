package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Una dependencia vista desde la tarea de la URL: {@code issue} es la otra tarea, que puede ser de otro proyecto del
 * workspace. {@code relationId} sirve para quitarla.
 *
 * @param accessible el usuario tiene acceso al proyecto de la otra tarea y puede abrirla. Si es {@code false}, de la
 *                   otra tarea solo se envían el código, el proyecto, el estado, la fecha límite y los contadores
 *                   ({@code title} viene {@code null} y {@code assignees} vacío)
 */
public record IssueRelationResponse(
        UUID relationId,
        RelatedIssueResponse issue,
        boolean accessible
) {
}
