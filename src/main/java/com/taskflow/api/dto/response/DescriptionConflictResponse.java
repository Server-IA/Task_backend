package com.taskflow.api.dto.response;

import java.time.Instant;

/**
 * Respuesta 409 al guardar una descripción que otra persona cambió después de la versión de la que partió quien
 * guarda ({@code baseDescriptionVersion}). Trae la descripción actual, su versión, quién la cambió y cuándo, y la
 * tarea completa tal como está ahora. No se cambió nada de la tarea.
 */
public record DescriptionConflictResponse(
        int status,
        /** Siempre {@code DESCRIPTION_CONFLICT}: distingue este 409 de los demás. */
        String code,
        String message,
        /** La descripción actual ({@code null} si la quitaron). */
        String description,
        int descriptionVersion,
        UserSummaryResponse descriptionUpdatedBy,
        Instant descriptionUpdatedAt,
        IssueResponse issue,
        Instant timestamp
) {

    public static final String CODE = "DESCRIPTION_CONFLICT";

    public static DescriptionConflictResponse of(String message, IssueResponse current) {
        return new DescriptionConflictResponse(409, CODE, message, current.description(),
                current.descriptionVersion(), current.descriptionUpdatedBy(), current.descriptionUpdatedAt(), current,
                Instant.now());
    }
}
