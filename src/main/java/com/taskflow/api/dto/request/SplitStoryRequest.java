package com.taskflow.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * «Partir en tareas»: las líneas que escribió el project manager, en orden. Se crean todas en una sola transacción
 * como hijas de la historia (si una falla, no se crea ninguna).
 */
public record SplitStoryRequest(
        @NotEmpty(message = "Escribe al menos una tarea")
        @Size(max = 50, message = "Máximo 50 tareas a la vez")
        List<@Valid SplitItemRequest> items,

        /**
         * La historia está cerrada y se le agregan tareas: {@code true} la reabre (si no, 409 con
         * {@code code: STORY_CLOSED}). Los bugs se ligan sin reabrirla.
         */
        Boolean reopen
) {
}
