package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Editar el texto de una nota de la retrospectiva (la columna no cambia).
 */
public record UpdateRetroNoteRequest(
        @NotBlank(message = "El texto es obligatorio")
        @Size(max = 500, message = "El texto no puede superar 500 caracteres")
        String text
) {
}
