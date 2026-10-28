package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Edición parcial de una nota de la retrospectiva (la columna no cambia): los campos nulos no se modifican.
 * {@code ownerId} cambia el responsable de una acción y {@code clearOwner: true} lo quita.
 */
public record UpdateRetroNoteRequest(
        @Size(min = 1, max = 500, message = "El texto debe tener entre 1 y 500 caracteres")
        String text,

        UUID ownerId,

        Boolean clearOwner
) {
}
