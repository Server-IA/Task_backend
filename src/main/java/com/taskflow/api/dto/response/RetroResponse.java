package com.taskflow.api.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Retrospectiva de un ciclo.
 *
 * @param started         está abierta (se abrió al cerrar el ciclo o la abrió el project manager); si no, no hay
 *                        notas
 * @param finishedAt      cuándo terminó; desde entonces las notas y los votos son de solo lectura
 * @param summary         resumen del ciclo
 * @param notes           notas de las tres columnas, de la más antigua a la más reciente
 * @param myVotesLeft     votos que le quedan al usuario que pide (máximo 5 por retrospectiva)
 * @param previousActions acciones de la retrospectiva del ciclo cerrado anterior, con su tarea si se convirtieron
 */
public record RetroResponse(
        boolean started,
        Instant finishedAt,
        RetroSummary summary,
        List<RetroNoteResponse> notes,
        int myVotesLeft,
        List<RetroNoteResponse> previousActions
) {

    /**
     * @param completedCount   tareas del ciclo completadas (grupo COMPLETED)
     * @param totalCount       tareas del ciclo más las que el cierre movió a otro ciclo o al backlog
     * @param carriedToCycle   tareas sin terminar que el cierre pasó a otro ciclo
     * @param carriedToBacklog tareas sin terminar que el cierre sacó del ciclo
     * @param cycleTimeDays    mediana de días entre la creación y el completado de las completadas (1 decimal);
     *                         {@code null} si no hay ninguna
     */
    public record RetroSummary(
            int completedCount,
            int totalCount,
            int carriedToCycle,
            int carriedToBacklog,
            Double cycleTimeDays
    ) {
    }
}
