package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ActivityField;

import java.time.Instant;
import java.util.UUID;

/**
 * Entrada del historial de una tarea. {@code updatedAt} solo difiere de {@code createdAt} en las ediciones de la
 * descripción: una sesión de edición es una sola entrada y {@code updatedAt} es su último guardado.
 */
public record ActivityResponse(
        UUID id,
        UserSummaryResponse actor,
        ActivityField field,
        String oldValue,
        String newValue,
        Instant createdAt,
        Instant updatedAt,
        /** Archivo de un {@code ATTACHMENT} mientras exista; {@code null} en los demás cambios o si se eliminó. */
        FileRefResponse file
) {
}
