package com.taskflow.api.entity.enums;

/**
 * Qué cambió en un issue. {@code CREATED} y {@code COMMENT} no son campos, sino eventos del historial.
 * {@code RELATION} registra una dependencia agregada ({@code newValue}) o quitada ({@code oldValue}) con un texto
 * legible: «bloquea WEB-12», «bloqueada por WEB-3» o «relacionada con WEB-7».
 * {@code DESCRIPTION_CHECKED} y {@code DESCRIPTION_UNCHECKED}: se marcó o desmarcó una casilla («- [ ] …») de la
 * descripción; {@code newValue} es su texto (Markdown de una línea).
 * {@code TYPE}: cambió el tipo ({@code STORY}, {@code TASK} o {@code BUG}).
 * {@code STORY_AUTO_STATE}: TaskFlow movió la historia sola (sin autor) porque empezó una de sus tareas (o la terminó
 * directo desde Backlog o Todo); {@code newValue} es el estado nuevo y {@code oldValue} lo que la movió
 * («Luis empezó WEB-41» o «Luis terminó WEB-41»).
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
    DESCRIPTION_UNCHECKED,
    TYPE,
    STORY_AUTO_STATE,
    /**
     * Se adjuntó ({@code newValue} = nombre del archivo) o se eliminó ({@code oldValue} = nombre) un archivo de la
     * sección «Adjuntos». Las imágenes pegadas en un texto no se registran.
     */
    ATTACHMENT
}
