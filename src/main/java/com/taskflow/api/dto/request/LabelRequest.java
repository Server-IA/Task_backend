package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Crear o editar una etiqueta.
 */
public record LabelRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 50, message = "El nombre no puede superar 50 caracteres")
        String name,

        @NotBlank(message = "El color es obligatorio")
        @Pattern(regexp = ValidationPatterns.HEX_COLOR, message = "El color debe tener el formato #RRGGBB")
        String color
) {
}
