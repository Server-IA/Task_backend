package com.taskflow.api.repository;

import java.util.UUID;

/**
 * Contadores de un proyecto para {@code ProjectResponse}. Tareas y bugs: las historias no cuentan en
 * {@code issueCount}, {@code openIssueCount} ni {@code overdueIssueCount}.
 *
 * @param openStoryCount    historias abiertas (ni completadas ni canceladas)
 * @param unsplitStoryCount de ellas, las que no tienen ninguna tarea ({@code TASK}) hija
 */
public record ProjectCounts(UUID projectId, int issueCount, int openIssueCount, int overdueIssueCount,
                            int openStoryCount, int unsplitStoryCount) {

    public static ProjectCounts empty(UUID projectId) {
        return new ProjectCounts(projectId, 0, 0, 0, 0, 0);
    }
}
