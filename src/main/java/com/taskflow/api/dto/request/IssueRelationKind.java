package com.taskflow.api.dto.request;

/**
 * Relación que se crea, vista desde la tarea de la URL.
 */
public enum IssueRelationKind {
    /** La tarea de la URL bloquea a la indicada. */
    BLOCKS,
    /** La tarea de la URL está bloqueada por la indicada (se guarda como «la indicada bloquea a esta»). */
    BLOCKED_BY,
    /** Relación simétrica entre ambas. */
    RELATES
}
