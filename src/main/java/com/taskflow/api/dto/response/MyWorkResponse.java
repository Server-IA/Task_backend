package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.StateGroup;

import java.util.List;

/**
 * Tareas abiertas del usuario en los proyectos a los que tiene acceso. Los contadores de las asignadas («tu semana»
 * y el encabezado) no dependen de la vista pedida.
 *
 * @param issues            tareas de la vista pedida, ordenadas por fecha límite (sin fecha al final),
 *                          prioridad (URGENT primero) y número
 * @param assignedCount     abiertas asignadas al usuario
 * @param createdCount      abiertas creadas por el usuario
 * @param commentedCount    abiertas en las que el usuario comentó
 * @param completedThisWeek asignadas al usuario y completadas esta semana (desde el lunes)
 * @param inProgressCount   asignadas al usuario en un estado del grupo STARTED (suma de los {@code byState} STARTED)
 * @param overdueCount      abiertas asignadas al usuario con fecha límite anterior a hoy
 * @param projectCount      proyectos distintos de las abiertas asignadas al usuario
 * @param byState           abiertas asignadas al usuario por estado (solo estados con alguna); ver
 *                          {@link StateCount}
 * @param nextDue           la abierta asignada al usuario con la fecha límite más próxima después de hoy (las de hoy
 *                          y las vencidas tienen su propio grupo); {@code null} si no hay
 */
public record MyWorkResponse(
        List<IssueResponse> issues,
        long assignedCount,
        long createdCount,
        long commentedCount,
        long completedThisWeek,
        long inProgressCount,
        long overdueCount,
        long projectCount,
        List<StateCount> byState,
        RelatedIssueResponse nextDue
) {

    /**
     * Cantidad de tareas en un estado. Los estados de distintos proyectos con el mismo nombre (sin importar
     * mayúsculas) y el mismo grupo se cuentan juntos; el nombre y el color son los del primero en el orden del
     * tablero. Orden: por grupo (BACKLOG, UNSTARTED, STARTED) y luego por posición en el tablero.
     */
    public record StateCount(String name, String color, StateGroup group, long count) {
    }
}
