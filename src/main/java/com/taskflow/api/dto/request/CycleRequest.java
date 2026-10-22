package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Crear un ciclo. Sin fechas queda como borrador (DRAFT).
 */
public record CycleRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 80, message = "El nombre no puede superar 80 caracteres")
        String name,

        @Size(max = 10000, message = "La descripción es demasiado larga")
        String description,

        LocalDate startDate,

        LocalDate endDate
) {
}
