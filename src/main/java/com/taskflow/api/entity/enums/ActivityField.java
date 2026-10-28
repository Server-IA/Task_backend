package com.taskflow.api.entity.enums;

/**
 * Qué cambió en un issue. {@code CREATED} y {@code COMMENT} no son campos, sino eventos del historial.
 * {@code RELATION} registra una dependencia agregada ({@code newValue}) o quitada ({@code oldValue}) con un texto
 * legible: «bloquea WEB-12», «bloqueada por WEB-3» o «relacionada con WEB-7».
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
    COMMENT,
    RELATION
}
