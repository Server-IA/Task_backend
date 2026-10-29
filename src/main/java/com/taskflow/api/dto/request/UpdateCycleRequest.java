package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Los campos nulos no se modifican. {@code clearDates} quita ambas fechas (vuelve a borrador);
 * {@code clearStartDate} y {@code clearEndDate} quitan solo una. Pedir quitar una fecha y a la vez enviarla es un
 * error (400).
 */
public record UpdateCycleRequest(
        @Size(min = 1, max = 80, message = "El nombre debe tener entre 1 y 80 caracteres")
        String name,

        @Size(max = 10000, message = "La descripción es demasiado larga")
        String description,

        LocalDate startDate,

        LocalDate endDate,

        Boolean clearDates,

        Boolean clearStartDate,

        Boolean clearEndDate
) {

    /** Sin quitar fechas sueltas (como antes de {@code clearStartDate} y {@code clearEndDate}). */
    public UpdateCycleRequest(String name, String description, LocalDate startDate, LocalDate endDate,
                              Boolean clearDates) {
        this(name, description, startDate, endDate, clearDates, null, null);
    }
}
