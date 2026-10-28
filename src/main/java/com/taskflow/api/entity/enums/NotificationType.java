package com.taskflow.api.entity.enums;

public enum NotificationType {
    ISSUE_ASSIGNED,
    ISSUE_COMMENTED,
    /** Se completó la última tarea abierta que bloqueaba a la tarea. */
    ISSUE_UNBLOCKED,
    /** Alguien mencionó al usuario en un comentario (con {@code @[Nombre](id)}). */
    ISSUE_MENTIONED,
    /** Se abrió la retrospectiva de un ciclo (la notificación lleva el ciclo, no una tarea). */
    RETRO_STARTED
}
