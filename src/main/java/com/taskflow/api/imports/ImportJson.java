package com.taskflow.api.imports;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * JSON de las importaciones: lo que se guarda en {@code import_jobs} (texto) y lo leído del origen (comprimido, en
 * {@code import_payloads}). Un campo que ya no exista en un registro guardado se ignora al leerlo.
 */
public final class ImportJson {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ImportJson() {
    }

    public static String write(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    /** @return el valor, o {@code null} si no hay texto */
    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("JSON de importación ilegible (" + type.getSimpleName() + ")", ex);
        }
    }

    public static byte[] compress(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            MAPPER.writeValue(gzip, value);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    public static <T> T decompress(byte[] data, Class<T> type) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return MAPPER.readValue(gzip, type);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
