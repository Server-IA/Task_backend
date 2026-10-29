package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Nuevo orden de las vistas («Ordenar vistas»). Cada lista, si viene, debe tener exactamente las vistas de su grupo
 * en el orden deseado: {@code shared} las compartidas del proyecto (solo el project manager) y {@code personal} mis
 * vistas personales. Al menos una de las dos.
 */
public record ReorderSavedViewsRequest(
        @Size(max = 200, message = "Máximo 200 vistas")
        List<@NotNull(message = "Las vistas no pueden ser nulas") UUID> shared,

        @Size(max = 200, message = "Máximo 200 vistas")
        List<@NotNull(message = "Las vistas no pueden ser nulas") UUID> personal
) {
}
