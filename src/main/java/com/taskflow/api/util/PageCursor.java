package com.taskflow.api.util;

import com.taskflow.api.exception.BadRequestException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Posición en un listado ordenado de lo más nuevo a lo más viejo ({@code createdAt} y, a igual fecha, {@code id}, los
 * dos descendentes). Como texto: {@code <createdAt ISO-8601>,<id>}, por ejemplo
 * {@code 2026-10-29T14:03:07.123456Z,6f1c0d2e-…}. {@code before=<cursor>} pide lo que viene justo después de ese
 * elemento.
 */
public record PageCursor(Instant createdAt, UUID id) {

    private static final String INVALID = "El cursor no es válido: se espera before=<createdAt>,<id>";
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    /** El cursor de {@code before}, o {@code null} si no viene. */
    public static PageCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int comma = value.lastIndexOf(',');
        if (comma <= 0 || comma == value.length() - 1) {
            throw new BadRequestException(INVALID);
        }
        Instant createdAt;
        UUID id;
        try {
            createdAt = Instant.parse(value.substring(0, comma).trim());
            id = UUID.fromString(value.substring(comma + 1).trim());
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            throw new BadRequestException(INVALID);
        }
        // Una fecha que PostgreSQL no admite (+1000000000-12-31T…) llegaba a la consulta y respondía «Datos inválidos:
        // el texto tiene caracteres no permitidos…»
        if (createdAt.isBefore(MIN) || createdAt.isAfter(MAX)) {
            throw new BadRequestException(INVALID);
        }
        return new PageCursor(createdAt, id);
    }

    public static String format(Instant createdAt, UUID id) {
        return createdAt + "," + id;
    }

    @Override
    public String toString() {
        return format(createdAt, id);
    }
}
