package com.taskflow.api.i18n;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traductor central: el texto en español es la clave. Las traducciones al inglés están en {@code i18n/en.json}:
 * <ul>
 *   <li>{@code exact}: texto en español → texto en inglés;</li>
 *   <li>{@code patterns}: para los mensajes con partes variables, una expresión regular del texto completo
 *       ({@code es}) y su reemplazo ({@code en}), donde {@code {1}} es el grupo 1 tal cual y {@code {t:1}} el grupo 1
 *       traducido (para un fragmento que también es texto del código).</li>
 * </ul>
 * Un texto sin traducción se devuelve en español. {@code MessageCoverageTest} recorre el código y falla si un mensaje
 * no tiene traducción.
 */
public final class Translations {

    private static final String RESOURCE = "/i18n/en.json";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(t:)?(\\d+)}");
    /** Un fragmento traducido puede traer otro dentro; más allá de esto se deja como está. */
    private static final int MAX_DEPTH = 3;

    private static final Catalog ENGLISH = load();

    private Translations() {
    }

    private record Rule(Pattern pattern, String replacement) {
    }

    private record Catalog(Map<String, String> exact, List<Rule> rules) {
    }

    /** El texto en el idioma pedido; el mismo texto si es español, {@code null} o no tiene traducción. */
    public static String translate(String text, Language language) {
        if (text == null || language != Language.EN) {
            return text;
        }
        String translated = lookup(text, 0);
        return translated != null ? translated : text;
    }

    /** Cada valor traducido, en el mismo orden; el mismo mapa si es español o {@code null}. */
    public static Map<String, String> translate(Map<String, String> texts, Language language) {
        if (texts == null || language != Language.EN) {
            return texts;
        }
        Map<String, String> translated = new LinkedHashMap<>();
        texts.forEach((key, value) -> translated.put(key, translate(value, language)));
        return translated;
    }

    /** Si el texto tiene traducción al inglés (exacta o por patrón). */
    public static boolean hasTranslation(String text) {
        return text != null && lookup(text, 0) != null;
    }

    private static String lookup(String text, int depth) {
        String exact = ENGLISH.exact().get(text);
        if (exact != null) {
            return exact;
        }
        for (Rule rule : ENGLISH.rules()) {
            Matcher matcher = rule.pattern().matcher(text);
            if (matcher.matches()) {
                return fill(rule.replacement(), matcher, depth);
            }
        }
        return null;
    }

    private static String fill(String replacement, Matcher groups, int depth) {
        Matcher placeholder = PLACEHOLDER.matcher(replacement);
        StringBuilder result = new StringBuilder();
        while (placeholder.find()) {
            String value = groups.group(Integer.parseInt(placeholder.group(2)));
            if (value == null) {
                value = "";
            } else if (placeholder.group(1) != null && depth < MAX_DEPTH) {
                String translated = lookup(value, depth + 1);
                value = translated != null ? translated : value;
            }
            placeholder.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        placeholder.appendTail(result);
        return result.toString();
    }

    private static Catalog load() {
        try (InputStream in = Translations.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Falta el recurso " + RESOURCE);
            }
            JsonNode root = JsonMapper.builder().build().readTree(in);
            Map<String, String> exact = new HashMap<>();
            root.path("exact").properties().forEach(entry -> exact.put(entry.getKey(), entry.getValue().asString()));
            List<Rule> rules = new ArrayList<>();
            for (JsonNode rule : root.path("patterns")) {
                rules.add(new Rule(Pattern.compile(rule.path("es").asString(), Pattern.DOTALL),
                        rule.path("en").asString()));
            }
            return new Catalog(Map.copyOf(exact), List.copyOf(rules));
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer " + RESOURCE, ex);
        }
    }
}
