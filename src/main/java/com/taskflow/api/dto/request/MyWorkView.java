package com.taskflow.api.dto.request;

import com.taskflow.api.exception.BadRequestException;

import java.util.Locale;

/**
 * Vistas de «Mi trabajo»: tareas asignadas al usuario, creadas por él o en las que comentó.
 */
public enum MyWorkView {
    ASSIGNED,
    CREATED,
    COMMENTED;

    /** Lee el parámetro {@code view} (sin importar mayúsculas); vacío = {@code assigned}. */
    public static MyWorkView from(String value) {
        if (value == null || value.isBlank()) {
            return ASSIGNED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("El parámetro view debe ser assigned, created o commented");
        }
    }
}
