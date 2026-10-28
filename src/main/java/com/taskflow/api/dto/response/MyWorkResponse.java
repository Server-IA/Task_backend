package com.taskflow.api.dto.response;

import java.util.List;

/**
 * Tareas abiertas del usuario en los proyectos a los que tiene acceso.
 *
 * @param issues            tareas de la vista pedida, ordenadas por fecha límite (sin fecha al final),
 *                          prioridad (URGENT primero) y número
 * @param assignedCount     abiertas asignadas al usuario
 * @param createdCount      abiertas creadas por el usuario
 * @param commentedCount    abiertas en las que el usuario comentó
 * @param completedThisWeek asignadas al usuario y completadas esta semana (desde el lunes)
 * @param inProgressCount   asignadas al usuario en un estado del grupo STARTED
 */
public record MyWorkResponse(
        List<IssueResponse> issues,
        long assignedCount,
        long createdCount,
        long commentedCount,
        long completedThisWeek,
        long inProgressCount
) {
}
