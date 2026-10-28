package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.exception.BadRequestException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Filtro por tipo del feed de actividad del workspace.
 */
public enum ActivityFeedType {
    /** Todas las entradas del historial. */
    ALL(EnumSet.allOf(ActivityField.class)),
    /** Cambios de estado (incluye completar una tarea, que es pasarla a un estado completado). */
    STATE(EnumSet.of(ActivityField.STATE)),
    COMMENTS(EnumSet.of(ActivityField.COMMENT)),
    ASSIGNMENTS(EnumSet.of(ActivityField.ASSIGNEES));

    private final Set<ActivityField> fields;

    ActivityFeedType(Set<ActivityField> fields) {
        this.fields = Collections.unmodifiableSet(fields);
    }

    public Set<ActivityField> fields() {
        return fields;
    }

    /** Lee el parámetro {@code type} (sin importar mayúsculas); vacío = {@code all}. */
    public static ActivityFeedType from(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("El parámetro type debe ser all, state, comments o assignments");
        }
    }
}
