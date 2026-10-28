package com.taskflow.api.entity.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Grupo al que pertenece un estado; define en qué etapa del flujo está un issue.
 */
public enum StateGroup {
    BACKLOG,
    UNSTARTED,
    STARTED,
    COMPLETED,
    CANCELLED;

    /** Grupos de las tareas cerradas: completadas o canceladas. */
    public static final Set<StateGroup> CLOSED = Collections.unmodifiableSet(EnumSet.of(COMPLETED, CANCELLED));

    /** Abierta = ni completada ni cancelada. */
    public boolean isOpen() {
        return !CLOSED.contains(this);
    }
}
