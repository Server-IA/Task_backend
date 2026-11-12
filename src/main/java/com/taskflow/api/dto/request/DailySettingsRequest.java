package com.taskflow.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Ajustes de la daily de un proyecto. Reemplaza todo lo guardado: un punto que no venga en {@code agenda} se quita.
 */
public record DailySettingsRequest(
        @Valid
        @NotNull(message = "Indica qué bloques se exponen")
        Sections sections,

        @Size(max = 12, message = "No puede haber más de 12 puntos a tratar")
        List<@NotBlank(message = "Un punto a tratar no puede estar vacío")
        @Size(max = 120, message = "Un punto a tratar no puede superar 120 caracteres") String> agenda
) {

    public record Sections(
            @NotNull(message = "Indica si se expone el resumen") Boolean summary,
            @NotNull(message = "Indica si se expone «Ayer»") Boolean yesterday,
            @NotNull(message = "Indica si se expone «Hoy»") Boolean today,
            @NotNull(message = "Indica si se exponen los bloqueos") Boolean blockers,
            @NotNull(message = "Indica si se expone la nota del día") Boolean note
    ) {
    }
}
