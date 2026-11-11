package com.taskflow.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.taskflow.api.i18n.Language;
import com.taskflow.api.i18n.Translations;

import java.time.Instant;
import java.util.Map;

/**
 * Formato común de error que devuelve toda la API. Los mensajes nacen en español y aquí, al armar la respuesta, se
 * pasan al idioma de la petición ({@link Translations}): es el único lugar donde se traducen los errores.
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
        return of(status, message, errors, code, Language.current());
    }

    /** Con el idioma dado en vez del de la petición en curso (para lo que responde fuera de los filtros). */
    public static ErrorResponse of(int status, String message, Map<String, String> errors, String code,
                                   Language language) {
        return new ErrorResponse(status, language.t(message), Translations.translate(errors, language), code,
                Instant.now());
    }
}
