package com.taskflow.api.entity.enums;

/**
 * Qué cambió en un issue. {@code CREATED} y {@code COMMENT} no son campos, sino eventos del historial.
 */
public enum ActivityField {
    CREATED,
    TITLE,
    DESCRIPTION,
    STATE,
    PRIORITY,
    PARENT,
    START_DATE,
    DUE_DATE,
    ASSIGNEES,
    LABELS,
    CYCLE,
    MODULE,
    COMMENT
}
