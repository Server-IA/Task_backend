package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Edición parcial: los campos nulos no se modifican. {@code position} es el nuevo índice (desde 0) entre los
 * elementos del mismo tipo de la tarea; si es mayor que el último, el elemento queda al final.
 */
public record UpdateChecklistItemRequest(
        @Size(min = 1, max = 500, message = "El texto debe tener entre 1 y 500 caracteres")
        String text,

        Boolean done,

        @Min(value = 0, message = "La posición no puede ser negativa")
        Integer position
) {
}
