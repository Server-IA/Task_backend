package com.taskflow.api.entity.enums;

/**
 * En qué va una importación. {@code DRAFT}: conectada, se está eligiendo qué traer. {@code FETCHING}: leyendo del
 * origen lo elegido. {@code READY}: leído; se revisan las equivalencias. {@code RUNNING}: escribiendo en TaskFlow.
 * Termina en {@code COMPLETED}, {@code FAILED} o {@code STOPPED} (la detuvo alguien); cualquiera de las tres se puede
 * deshacer ({@code UNDOING} → {@code UNDONE}).
 */
public enum ImportStatus {
    DRAFT, FETCHING, READY, RUNNING, COMPLETED, FAILED, STOPPED, UNDOING, UNDONE;

    /** Ya escribió (algo) en TaskFlow y no se ha deshecho. */
    public boolean finished() {
        return this == COMPLETED || this == FAILED || this == STOPPED;
    }

    /** Hay un trabajo corriendo en segundo plano. */
    public boolean working() {
        return this == FETCHING || this == RUNNING || this == UNDOING;
    }
}
