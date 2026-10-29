package com.taskflow.api.dto.response;

import java.time.LocalDate;

/**
 * Estado de un ciclo según sus fechas y el día de hoy. Un ciclo cerrado está COMPLETED aunque sus fechas no
 * hayan terminado.
 */
public enum CycleStatus {
    DRAFT,
    UPCOMING,
    CURRENT,
    COMPLETED;

    public static CycleStatus of(LocalDate startDate, LocalDate endDate, LocalDate today) {
        return of(startDate, endDate, false, today);
    }

    public static CycleStatus of(LocalDate startDate, LocalDate endDate, boolean closed, LocalDate today) {
        if (closed) {
            return COMPLETED;
        }
        if (startDate == null || endDate == null) {
            return DRAFT;
        }
        if (today.isBefore(startDate)) {
            return UPCOMING;
        }
        if (today.isAfter(endDate)) {
            return COMPLETED;
        }
        return CURRENT;
    }
}
