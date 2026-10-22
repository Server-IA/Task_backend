package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Los campos nulos no se modifican. {@code clearDates} quita ambas fechas.
 */
public record UpdateModuleRequest(
        @Size(min = 1, max = 80, message = "El nombre debe tener entre 1 y 80 caracteres")
        String name,

        @Size(max = 10000, message = "La descripción es demasiado larga")
        String description,

        LocalDate startDate,

        LocalDate targetDate,

        Boolean clearDates
) {
}
