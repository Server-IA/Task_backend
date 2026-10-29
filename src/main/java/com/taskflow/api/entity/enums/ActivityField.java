package com.taskflow.api.entity.enums;

/**
 * Qué cambió en un issue. {@code CREATED} y {@code COMMENT} no son campos, sino eventos del historial.
 * {@code RELATION} registra una dependencia agregada ({@code newValue}) o quitada ({@code oldValue}) con un texto
 * legible: «bloquea WEB-12», «bloqueada por WEB-3» o «relacionada con WEB-7».
 * {@code DESCRIPTION_CHECKED} y {@code DESCRIPTION_UNCHECKED}: se marcó o desmarcó una casilla («- [ ] …») de la
 * descripción; {@code newValue} es su texto (Markdown de una línea).
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
    RELATION,
    DESCRIPTION_CHECKED,
    DESCRIPTION_UNCHECKED
}
