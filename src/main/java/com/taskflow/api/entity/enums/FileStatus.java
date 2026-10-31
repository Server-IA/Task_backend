package com.taskflow.api.entity.enums;

/**
 * Estado de una subida.
 */
public enum FileStatus {
    /** Se firmó la subida y el navegador la está mandando a R2. Sin confirmar en 24 h, se borra. */
    PENDING,
    /** Confirmada: el backend comprobó en R2 su tamaño y su tipo. */
    READY
}
