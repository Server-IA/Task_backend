package com.taskflow.api.i18n;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Saca del código fuente los textos que el backend le escribe a una persona: los mensajes de las excepciones de la
 * API, los de validación ({@code message = "…"}) y cualquier otro literal que parezca una frase en español
 * (constantes de mensajes, textos del PDF, descripciones…).
 * <p>
 * Un mensaje armado con partes variables ({@code "Falta el parámetro '" + name + "'"}, {@code "«%s»"}) sale con un
 * {@value #SAMPLE} en cada parte variable: sirve igual para un patrón de texto que para uno de números.
 * No se miran los comentarios, las anotaciones (salvo {@code message}), los {@code log.…(…)} ni las excepciones que
 * no son de la API (las de arranque o internas, que no llegan a nadie).
 */
final class SourceMessages {

    /** Lo que ocupa el lugar de cada parte variable de un mensaje. */
    static final String SAMPLE = "7";

    enum Kind {
        /** Primer argumento de una excepción de la API. */
        EXCEPTION,
        /** {@code message = "…"} de una anotación de validación. */
        VALIDATION,
        /** Otro literal que parece una frase en español. */
        TEXT
    }

    record Found(String file, int line, Kind kind, String text) {
    }

    private static final char OPEN = '\u0001';
    private static final char CLOSE = '\u0002';
    private static final Pattern LITERAL = Pattern.compile(OPEN + "(\\d+)" + CLOSE);
    private static final Pattern PURE_LITERAL = Pattern.compile("^" + OPEN + "(\\d+)" + CLOSE + "(\\s*\\..*)?$",
            Pattern.DOTALL);
    private static final Pattern ANNOTATION = Pattern.compile("@[A-Za-z_][\\w.]*\\s*\\(");
    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern NEW_CALL = Pattern.compile("\\bnew\\s+([\\w.]+)\\s*\\(");
    private static final Pattern SUBCLASS = Pattern.compile("\\bclass\\s+(\\w+)\\s+extends\\s+(\\w+)");
    /** Lo que va antes del valor en {@code return "…"} o {@code String message() default "…"}. */
    private static final Pattern LEADING_KEYWORD = Pattern.compile("^.*\\b(return|yield|throw|default|case|else)\\b",
            Pattern.DOTALL);
    private static final Pattern FORMAT_SPEC = Pattern.compile("%[-,0-9.]*[sd]");
    private static final Pattern MESSAGE_ATTRIBUTE = Pattern.compile("\\bmessage\\s*$");
    /** Dos palabras seguidas en minúsculas, o una letra o signo propio del español: parece una frase. */
    private static final Pattern SENTENCE = Pattern.compile("\\p{Ll}{2,} \\p{Ll}{2,}|[áéíóúñÁÉÍÓÚÑ¿¡«»]");

    private SourceMessages() {
    }

    static List<Found> scan(Path root) {
        Map<Path, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                sources.put(file, Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Set<String> apiExceptions = apiExceptions(sources.values());
        List<Found> found = new ArrayList<>();
        sources.keySet().stream().sorted().forEach(file ->
                found.addAll(scan(root.relativize(file).toString().replace('\\', '/'), sources.get(file),
                        apiExceptions)));
        return found;
    }

    /** {@code ApiException} y todo lo que la extiende, directa o indirectamente. */
    private static Set<String> apiExceptions(Iterable<String> sources) {
        Map<String, String> parents = new HashMap<>();
        for (String source : sources) {
            Matcher matcher = SUBCLASS.matcher(source);
            while (matcher.find()) {
                parents.put(matcher.group(1), matcher.group(2));
            }
        }
        Set<String> result = new HashSet<>(Set.of("ApiException"));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, String> entry : parents.entrySet()) {
                if (result.contains(entry.getValue()) && result.add(entry.getKey())) {
                    grew = true;
                }
            }
        }
        return result;
    }

    static List<Found> scan(String file, String source, Set<String> apiExceptions) {
        List<String> literals = new ArrayList<>();
        String code = mask(source, literals);

        List<int[]> annotations = ranges(code, ANNOTATION, null, null);
        List<int[]> skipped = ranges(code, LOG_CALL, null, null);
        // Excepciones que no son de la API: sus mensajes no llegan a nadie
        skipped.addAll(ranges(code, NEW_CALL, apiExceptions, Boolean.FALSE));
        List<int[]> apiCalls = ranges(code, NEW_CALL, apiExceptions, Boolean.TRUE);

        List<Found> found = new ArrayList<>();
        Set<Integer> consumed = new HashSet<>();
        Matcher literal = LITERAL.matcher(code);
        while (literal.find()) {
            int index = Integer.parseInt(literal.group(1));
            if (consumed.contains(index) || inside(skipped, literal.start())) {
                continue;
            }
            int left = leftLimit(code, literal.start());
            int right = rightLimit(code, literal.end());
            String rendered = render(code.substring(left + 1, right), literals, consumed);
            consumed.add(index);
            if (rendered == null) {
                continue;
            }
            Kind kind;
            if (inside(annotations, literal.start())) {
                boolean isMessage = left >= 0 && code.charAt(left) == '='
                        && MESSAGE_ATTRIBUTE.matcher(code.substring(Math.max(0, left - 40), left)).find();
                if (!isMessage) {
                    continue;
                }
                kind = Kind.VALIDATION;
            } else if (startsRange(apiCalls, left)) {
                kind = Kind.EXCEPTION;
            } else if (SENTENCE.matcher(rendered).find()) {
                kind = Kind.TEXT;
            } else {
                continue;
            }
            found.add(new Found(file, lineOf(code, literal.start()), kind, rendered));
        }
        return found;
    }

    // ---------------------------------------------------------------- lectura del código

    /**
     * El código sin comentarios y con cada literal de texto cambiado por una marca con su número (los literales
     * quedan en {@code literals}); los saltos de línea se conservan para poder decir en qué línea está cada uno.
     */
    private static String mask(String source, List<String> literals) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                appendNewlines(out, source, i, end);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end;
                out.append(OPEN).append(literals.size()).append(CLOSE);
                literals.add(source.substring(i + 3, end));
                appendNewlines(out, source, i, end);
                i = Math.min(n, end + 3);
            } else if (c == '"') {
                StringBuilder value = new StringBuilder();
                i++;
                while (i < n && source.charAt(i) != '"') {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        char next = source.charAt(i + 1);
                        if (next == 'u' && i + 5 < n) {
                            value.append((char) Integer.parseInt(source.substring(i + 2, i + 6), 16));
                            i += 6;
                            continue;
                        }
                        value.append(switch (next) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            case '0' -> '\0';
                            default -> next;
                        });
                        i += 2;
                    } else {
                        value.append(ch);
                        i++;
                    }
                }
                i++;
                out.append(OPEN).append(literals.size()).append(CLOSE);
                literals.add(value.toString());
            } else if (c == '\'') {
                // Un carácter ('"', '\'', 'a'): cuenta como una parte que no es texto
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.append('0');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void appendNewlines(StringBuilder out, String source, int from, int to) {
        for (int i = from; i < to; i++) {
            if (source.charAt(i) == '\n') {
                out.append('\n');
            }
        }
    }

    /**
     * Los paréntesis de cada llamada que encaja con {@code pattern} (desde «(» hasta su «)»). Con {@code names}, solo
     * las de {@code new Nombre(} cuyo nombre termina en Exception o Error y está ({@code member}) o no está en
     * {@code names}.
     */
    private static List<int[]> ranges(String code, Pattern pattern, Set<String> names, Boolean member) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            if (names != null) {
                String name = matcher.group(1).substring(matcher.group(1).lastIndexOf('.') + 1);
                boolean exception = name.endsWith("Exception") || name.endsWith("Error");
                if (!exception || names.contains(name) != member) {
                    continue;
                }
            }
            int open = matcher.end() - 1;
            ranges.add(new int[]{open, closing(code, open)});
        }
        return ranges;
    }

    private static int closing(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    private static boolean inside(List<int[]> ranges, int position) {
        return ranges.stream().anyMatch(range -> position > range[0] && position < range[1]);
    }

    private static boolean startsRange(List<int[]> ranges, int open) {
        return ranges.stream().anyMatch(range -> range[0] == open);
    }

    /** Hacia la izquierda desde el literal: dónde empieza la expresión de la que forma parte. */
    private static int leftLimit(String code, int from) {
        int depth = 0;
        for (int i = from - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (depth == 0 && (",;{}?=:".indexOf(c) >= 0 || (c == '>' && i > 0 && code.charAt(i - 1) == '-'))) {
                return i;
            }
        }
        return -1;
    }

    /** Hacia la derecha desde el literal: dónde termina la expresión. */
    private static int rightLimit(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (depth == 0 && ",;{}?:".indexOf(c) >= 0) {
                return i;
            }
        }
        return code.length();
    }

    /**
     * El texto de una expresión {@code "a" + x + "b"}: los literales tal cual y cada parte variable como
     * {@link #SAMPLE}; {@code null} si no tiene ningún literal directo.
     */
    private static String render(String expression, List<String> literals, Set<Integer> consumed) {
        String body = LEADING_KEYWORD.matcher(expression).replaceFirst("");
        StringBuilder text = new StringBuilder();
        boolean anyLiteral = false;
        for (String operand : splitByPlus(body)) {
            Matcher pure = PURE_LITERAL.matcher(operand.strip());
            if (pure.matches()) {
                int index = Integer.parseInt(pure.group(1));
                consumed.add(index);
                text.append(literals.get(index));
                anyLiteral = true;
            } else {
                text.append(SAMPLE);
            }
        }
        return anyLiteral ? FORMAT_SPEC.matcher(text).replaceAll(SAMPLE) : null;
    }

    private static List<String> splitByPlus(String expression) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == '+' && depth == 0) {
                parts.add(expression.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(expression.substring(start));
        return parts;
    }

    private static int lineOf(String code, int position) {
        int line = 1;
        for (int i = 0; i < position; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
