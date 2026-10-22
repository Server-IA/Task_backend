package com.taskflow.api.entity.enums;

/**
 * Grupo al que pertenece un estado; define en qué etapa del flujo está un issue.
 */
public enum StateGroup {
    BACKLOG,
    UNSTARTED,
    STARTED,
    COMPLETED,
    CANCELLED
}
