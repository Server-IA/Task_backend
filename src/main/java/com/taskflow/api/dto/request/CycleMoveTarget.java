package com.taskflow.api.dto.request;

/**
 * A dónde va una tarea sin terminar al cerrar un ciclo.
 */
public enum CycleMoveTarget {
    /** Al ciclo destino (por defecto, el siguiente ciclo sin cerrar). */
    NEXT_CYCLE,
    /** Sale del ciclo y queda sin ciclo; su estado no cambia. */
    BACKLOG
}
