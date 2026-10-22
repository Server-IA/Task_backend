package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.StateGroup;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Los campos nulos no se modifican.
 */
public record UpdateStateRequest(
        @Size(min = 1, max = 50, message = "El nombre debe tener entre 1 y 50 caracteres")
        String name,

        @Pattern(regexp = ValidationPatterns.HEX_COLOR, message = "El color debe tener el formato #RRGGBB")
        String color,

        StateGroup group,

        @PositiveOrZero(message = "La posición no puede ser negativa")
        Integer position
) {
}
