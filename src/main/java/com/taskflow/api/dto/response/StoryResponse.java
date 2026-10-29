package com.taskflow.api.dto.response;

import java.util.List;

/**
 * Una fila de «Historias»: la historia (con su progreso, «lista para revisar», bugs y quién la cerró), las personas
 * de sus tareas, sus tareas y bugs (primero las tareas, por número) y las tareas relacionadas con ella
 * ({@code RELATES}, por ejemplo la historia nueva con el alcance que no entró).
 */
public record StoryResponse(
        IssueResponse story,
        List<UserSummaryResponse> people,
        List<StoryChildResponse> children,
        List<IssueSummaryResponse> related
) {
}
