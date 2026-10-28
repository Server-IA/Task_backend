package com.taskflow.api.dto.response;

import java.util.List;

/**
 * Lo que pasará al cerrar un ciclo.
 *
 * @param cycle           el ciclo
 * @param completed       sus tareas completadas (grupo COMPLETED), por número
 * @param unfinished      sus tareas sin terminar (ni completadas ni canceladas), por número: las que se moverán
 * @param completedCount  cantidad de {@code completed}
 * @param unfinishedCount cantidad de {@code unfinished}
 * @param cycleTimeDays   mediana de días entre la creación y el completado de las completadas (1 decimal);
 *                        {@code null} si no hay ninguna
 * @param nextCycle       destino por defecto de las tareas sin terminar: el siguiente ciclo sin cerrar por fecha de
 *                        inicio; {@code null} si no hay ninguno
 */
public record CloseCyclePreviewResponse(
        CycleResponse cycle,
        List<RelatedIssueResponse> completed,
        List<RelatedIssueResponse> unfinished,
        int completedCount,
        int unfinishedCount,
        Double cycleTimeDays,
        CycleResponse nextCycle
) {
}
