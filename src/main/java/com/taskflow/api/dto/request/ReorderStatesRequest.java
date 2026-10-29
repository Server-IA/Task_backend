package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * El orden nuevo de las columnas del tablero: todos los estados del proyecto, cada uno una vez, de izquierda a
 * derecha.
 */
public record ReorderStatesRequest(
        @NotNull(message = "stateIds es obligatorio")
        @Size(min = 1, max = 100, message = "stateIds debe tener entre 1 y 100 estados")
        List<@NotNull(message = "stateIds no puede tener valores nulos") UUID> stateIds
) {
}
