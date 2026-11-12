package com.taskflow.api.imports.source;

import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Lo que los conectores de Trello, Linear y Asana hacen igual con lo que leen: sacar un texto o una fecha de una
 * respuesta sin que un campo ausente o {@code null} rompa la lectura, y revisar la credencial que escribió la persona.
 */
final class SourceValues {

    private SourceValues() {
    }

    /** El texto de un campo (o un número, como texto); cadena vacía si falta, es {@code null} o es otra cosa. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : value.isNumber() ? value.asString() : "";
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** Una fecha y hora ISO 8601 («2026-11-01T10:00:00.000Z»); {@code null} si no lo es. */
    static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text.strip()).toInstant();
        } catch (DateTimeParseException notIso) {
            return null;
        }
    }

    /** El día de una fecha («2026-12-01») o de una fecha y hora (su día en UTC, que es como la da el origen). */
    static LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.strip();
        try {
            return LocalDate.parse(clean.length() > 10 ? clean.substring(0, 10) : clean);
        } catch (DateTimeParseException notDate) {
            return null;
        }
    }

    /**
     * La clave o el token que escribió la persona, sin espacios alrededor. Uno vacío o con algo que no cabe en una
     * cabecera HTTP no puede ser bueno: se rechaza aquí, porque el error del cliente HTTP citaría el valor y no debe
     * llegar a un registro.
     */
    static String credential(String value) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty() || !clean.chars().allMatch(c -> c > 0x20 && c < 0x7F && c != '"')) {
            throw new SourceException(SourceHttp.REJECTED);
        }
        return clean;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
