package com.taskflow.api.dto.response;

import java.time.LocalDate;

/**
 * Estado de un ciclo según sus fechas y el día de hoy.
 */
public enum CycleStatus {
    DRAFT,
    UPCOMING,
    CURRENT,
    COMPLETED;

    public static CycleStatus of(LocalDate startDate, LocalDate endDate, LocalDate today) {
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
