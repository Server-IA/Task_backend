package com.taskflow.api.dto.response;

/**
 * Espacio para archivos de un workspace: adjuntos e imágenes de sus tareas (las fotos de perfil no cuentan).
 *
 * @param usedBytes  archivos confirmados (al pedir una subida también cuentan las que están en curso)
 * @param quotaBytes 2 GB
 * @param percent    0 a 100 (redondeado hacia abajo)
 * @param warning    pasó el 90 %
 * @param full       llegó al 100 %: no se pueden subir archivos nuevos
 */
public record StorageUsageResponse(
        long usedBytes,
        long quotaBytes,
        int percent,
        boolean warning,
        boolean full
) {

    /** Uso con {@code usedBytes} de {@code quotaBytes}. */
    public static StorageUsageResponse of(long usedBytes, long quotaBytes) {
        long used = Math.max(0, usedBytes);
        int percent = quotaBytes <= 0 ? 100 : (int) Math.min(100, used * 100 / quotaBytes);
        return new StorageUsageResponse(used, quotaBytes, percent, used * 10 >= quotaBytes * 9L, used >= quotaBytes);
    }
}
