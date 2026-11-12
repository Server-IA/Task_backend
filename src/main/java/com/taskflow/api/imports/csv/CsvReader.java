package com.taskflow.api.imports.csv;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Lee un archivo CSV como lo exportan las herramientas de tareas y las hojas de cálculo: campos entre comillas que
 * pueden llevar comas, saltos de línea y comillas dobladas («""»), separador coma, punto y coma o tabulación (se
 * detecta en la primera línea) y texto en UTF-8 (con o sin marca) o, si no lo es, en Windows-1252.
 */
public final class CsvReader {

    private static final char[] DELIMITERS = {',', ';', '\t'};

    private CsvReader() {
    }

    /**
     * @param delimiter el separador que se detectó
     * @param header    los nombres de las columnas (se conservan los repetidos: Jira repite «Labels», «Sprint»…)
     * @param rows      las filas, cada una con tantos valores como columnas (se rellena con vacíos si trae menos)
     */
    public record Table(char delimiter, List<String> header, List<List<String>> rows) {
    }

    public static Table read(byte[] bytes) {
        String text = decode(bytes);
        char delimiter = detectDelimiter(text);
        List<List<String>> records = parse(text, delimiter);
        if (records.isEmpty()) {
            return new Table(delimiter, List.of(), List.of());
        }
        List<String> header = records.get(0).stream().map(String::strip).toList();
        List<List<String>> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            // Una línea en blanco entre filas llega como un solo valor vacío
            if (record.stream().allMatch(String::isBlank)) {
                continue;
            }
            List<String> row = new ArrayList<>(record);
            while (row.size() < header.size()) {
                row.add("");
            }
            rows.add(row);
        }
        return new Table(delimiter, header, rows);
    }

    static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            // Excel en Windows guarda «CSV (delimitado por comas)» en la página de códigos del sistema
            return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    /** El separador que más veces aparece, fuera de comillas, en la primera línea. */
    static char detectDelimiter(String text) {
        int[] counts = new int[DELIMITERS.length];
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted) {
                for (int d = 0; d < DELIMITERS.length; d++) {
                    if (c == DELIMITERS[d]) {
                        counts[d]++;
                    }
                }
            }
        }
        int best = 0;
        for (int d = 1; d < DELIMITERS.length; d++) {
            if (counts[d] > counts[best]) {
                best = d;
            }
        }
        return DELIMITERS[best];
    }

    static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.isEmpty()) {
                quoted = true;
                any = true;
            } else if (c == delimiter) {
                record.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || !field.isEmpty()) {
                    record.add(field.toString());
                    records.add(record);
                }
                record = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || !field.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
