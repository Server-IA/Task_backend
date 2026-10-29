package com.taskflow.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Formato común de error que devuelve toda la API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String message,
        Map<String, String> errors,
        /** Distingue un error de otros con el mismo estado (por ejemplo {@code STORY_CLOSED}); casi siempre null. */
        String code,
        Instant timestamp
) {

    public static ErrorResponse of(int status, String message) {
        return of(status, message, null, null);
    }

    public static ErrorResponse of(int status, String message, Map<String, String> errors) {
        return of(status, message, errors, null);
    }

    public static ErrorResponse of(int status, String message, Map<String, String> errors, String code) {
        return new ErrorResponse(status, message, errors, code, Instant.now());
    }
}
