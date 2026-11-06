package com.taskflow.api.dto.response;

/**
 * Resultado de cerrar un ciclo.
 *
 * @param cycle          el ciclo ya cerrado
 * @param movedToCycle   tareas sin terminar que pasaron al ciclo destino
 * @param movedToBacklog tareas sin terminar que salieron del ciclo (quedaron sin ciclo)
 * @param retroStarted    la retrospectiva del ciclo está abierta (también si ya lo estaba desde antes)
 * @param retroStartedNow este cierre abrió la retrospectiva y avisó al equipo; false si no se pidió
 *                        ({@code startRetro}) o si ya estaba abierta (no se vuelve a avisar)
 */
public record CloseCycleResponse(
        CycleResponse cycle,
        int movedToCycle,
        int movedToBacklog,
        boolean retroStarted,
        boolean retroStartedNow
) {
}
