package com.taskflow.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Ajustes de la daily de un proyecto. Reemplaza todo lo guardado: una persona que no venga en {@code personTimes}
 * vuelve al tiempo general y un punto que no venga en {@code agenda} se quita.
 */
public record DailySettingsRequest(
        @NotNull(message = "El tiempo por persona es obligatorio")
        @Min(value = 15, message = "El tiempo por persona no puede ser menor de 15 segundos")
        @Max(value = 3600, message = "El tiempo por persona no puede superar 60 minutos")
        Integer secondsPerPerson,

        @Valid
        @Size(max = 200, message = "No puede haber más de 200 tiempos por persona")
        List<@NotNull(message = "Un tiempo por persona no puede estar vacío") @Valid PersonTime> personTimes,

        @Valid
        @NotNull(message = "Indica qué bloques se exponen")
        Sections sections,

        @Size(max = 12, message = "No puede haber más de 12 puntos a tratar")
        List<@NotBlank(message = "Un punto a tratar no puede estar vacío")
        @Size(max = 120, message = "Un punto a tratar no puede superar 120 caracteres") String> agenda
) {

    public record PersonTime(
            @NotNull(message = "Falta la persona")
            UUID userId,

            @NotNull(message = "Falta el tiempo de la persona")
            @Min(value = 15, message = "El tiempo de una persona no puede ser menor de 15 segundos")
            @Max(value = 3600, message = "El tiempo de una persona no puede superar 60 minutos")
            Integer seconds
    ) {
    }

    public record Sections(
            @NotNull(message = "Indica si se expone el resumen") Boolean summary,
            @NotNull(message = "Indica si se expone «Ayer»") Boolean yesterday,
            @NotNull(message = "Indica si se expone «Hoy»") Boolean today,
            @NotNull(message = "Indica si se exponen los bloqueos") Boolean blockers,
            @NotNull(message = "Indica si se expone la nota del día") Boolean note
    ) {
    }
}
