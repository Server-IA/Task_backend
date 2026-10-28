package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Nota de la daily de un día. Un texto vacío (o {@code null}) la borra.
 */
public record DailyNoteRequest(
        @Size(max = 5000, message = "La nota no puede superar 5000 caracteres")
        String text
) {
}
