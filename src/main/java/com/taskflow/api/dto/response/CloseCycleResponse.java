package com.taskflow.api.dto.response;

/**
 * Resultado de cerrar un ciclo.
 *
 * @param cycle          el ciclo ya cerrado
 * @param movedToCycle   tareas sin terminar que pasaron al ciclo destino
 * @param movedToBacklog tareas sin terminar que salieron del ciclo (quedaron sin ciclo)
 * @param retroStarted   la retrospectiva del ciclo está abierta
 */
public record CloseCycleResponse(
        CycleResponse cycle,
        int movedToCycle,
        int movedToBacklog,
        boolean retroStarted
) {
}
