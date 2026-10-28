package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Una dependencia vista desde la tarea de la URL: {@code issue} es la otra tarea. {@code relationId} sirve para
 * quitarla.
 */
public record IssueRelationResponse(
        UUID relationId,
        RelatedIssueResponse issue
) {
}
