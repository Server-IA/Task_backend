package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.StateGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Si no se envía la posición, el estado se agrega al final del tablero.
 */
public record CreateStateRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 50, message = "El nombre no puede superar 50 caracteres")
        String name,

        @NotBlank(message = "El color es obligatorio")
        @Pattern(regexp = ValidationPatterns.HEX_COLOR, message = "El color debe tener el formato #RRGGBB")
        String color,

        @NotNull(message = "El grupo es obligatorio")
        StateGroup group,

        @PositiveOrZero(message = "La posición no puede ser negativa")
        Integer position
) {
}
