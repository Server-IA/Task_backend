package com.taskflow.api.entity.enums;

/**
 * Tipo de relación entre dos tareas del mismo proyecto.
 */
public enum IssueRelationType {
    /** La tarea origen bloquea a la destino (la destino está «bloqueada por» la origen). */
    BLOCKS,
    /** Relación simétrica: se guarda una sola vez, en cualquier dirección. */
    RELATES
}
