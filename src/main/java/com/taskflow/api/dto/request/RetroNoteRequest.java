package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.RetroColumn;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Nota nueva en la retrospectiva: columna y texto (de 1 a 500 caracteres). {@code ownerId} es el responsable de una
 * acción ({@code ACTION}): debe ser miembro del proyecto.
 */
public record RetroNoteRequest(
        @NotNull(message = "La columna es obligatoria: WENT_WELL, IMPROVE o ACTION")
        RetroColumn column,

        @NotBlank(message = "El texto es obligatorio")
        @Size(max = 500, message = "El texto no puede superar 500 caracteres")
        String text,

        UUID ownerId
) {
}
