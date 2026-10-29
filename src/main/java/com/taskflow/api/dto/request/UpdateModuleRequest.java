package com.taskflow.api.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Los campos nulos no se modifican. {@code clearDates} quita ambas fechas; {@code clearStartDate} y
 * {@code clearTargetDate} (también se acepta {@code clearEndDate}) quitan solo una. Pedir quitar una fecha y a la vez
 * enviarla es un error (400).
 */
public record UpdateModuleRequest(
        @Size(min = 1, max = 80, message = "El nombre debe tener entre 1 y 80 caracteres")
        String name,

        @Size(max = 10000, message = "La descripción es demasiado larga")
        String description,

        LocalDate startDate,

        LocalDate targetDate,

        Boolean clearDates,

        Boolean clearStartDate,

        @JsonAlias("clearEndDate")
        Boolean clearTargetDate
) {

    /** Sin quitar fechas sueltas (como antes de {@code clearStartDate} y {@code clearTargetDate}). */
    public UpdateModuleRequest(String name, String description, LocalDate startDate, LocalDate targetDate,
                               Boolean clearDates) {
        this(name, description, startDate, targetDate, clearDates, null, null);
    }
}
