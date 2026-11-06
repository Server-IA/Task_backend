package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Edición parcial de una nota de la retrospectiva (la columna no cambia): los campos nulos no se modifican.
 * {@code ownerId} cambia el responsable de una acción y {@code clearOwner: true} lo quita.
 */
public record UpdateRetroNoteRequest(
        @Size(max = 500, message = "El texto no puede superar 500 caracteres")
        @Visible(message = "El texto no puede estar vacío")
        String text,

        UUID ownerId,

        Boolean clearOwner
) {
}
