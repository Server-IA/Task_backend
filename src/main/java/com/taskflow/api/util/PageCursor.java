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

    /** El cursor de {@code before}, o {@code null} si no viene. */
    public static PageCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int comma = value.lastIndexOf(',');
        if (comma <= 0 || comma == value.length() - 1) {
            throw new BadRequestException(INVALID);
        }
        try {
            return new PageCursor(Instant.parse(value.substring(0, comma).trim()),
                    UUID.fromString(value.substring(comma + 1).trim()));
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            throw new BadRequestException(INVALID);
        }
    }

    public static String format(Instant createdAt, UUID id) {
        return createdAt + "," + id;
    }

    @Override
    public String toString() {
        return format(createdAt, id);
    }
}
