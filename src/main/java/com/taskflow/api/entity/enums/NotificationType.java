package com.taskflow.api.entity.enums;

public enum NotificationType {
    ISSUE_ASSIGNED,
    ISSUE_COMMENTED,
    /** Se completó la última tarea abierta que bloqueaba a la tarea. */
    ISSUE_UNBLOCKED,
    /** Alguien mencionó al usuario en un comentario (con {@code @[Nombre](id)}). */
    ISSUE_MENTIONED,
    /** Se abrió la retrospectiva de un ciclo (la notificación lleva el ciclo, no una tarea). */
    RETRO_STARTED,
    /**
     * Una historia quedó «lista para revisar» (todas sus tareas terminadas y ningún bug ligado abierto). Lo manda
     * TaskFlow (sin autor) a los project managers del workspace.
     */
    STORY_READY,
    /**
     * El workspace pasó el 90 % de su espacio para archivos. Lo manda TaskFlow (sin autor) a sus project managers; la
     * notificación lleva el workspace, no una tarea.
     */
    STORAGE_WARNING
}
