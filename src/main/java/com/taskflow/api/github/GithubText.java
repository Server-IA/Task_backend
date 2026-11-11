package com.taskflow.api.github;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lo que la integración lee y escribe como texto: los códigos de tarea que nombra una rama o un pull request, el nombre
 * de la rama de una tarea y qué datos pide un workflow para ejecutarse a mano.
 */
public final class GithubText {

    /** Un código de tarea: el identificador del proyecto (empieza por letra, hasta 12 letras o números), guion y número. */
    private static final Pattern CODE_PATTERN = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]{0,11})-(\\d{1,9})(?![A-Za-z0-9])");
    private static final int BRANCH_MAX = 80;

    private GithubText() {
    }

    public record Code(String identifier, int number) {
    }

    /**
     * Los códigos de tarea que aparecen en los textos, en orden y sin repetir: «web-22-rotacion» nombra a WEB-22. Que
     * exista esa tarea lo decide quien busca; aquí solo se reconoce la forma.
     */
    public static List<Code> codes(String... texts) {
        Set<Code> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = CODE_PATTERN.matcher(text);
            while (matcher.find()) {
                found.add(new Code(matcher.group(1).toUpperCase(Locale.ROOT), Integer.parseInt(matcher.group(2))));
            }
        }
        return new ArrayList<>(found);
    }

    /** Cómo nombra TaskFlow las ramas de un proyecto. */
    public static final String CODE_TITLE = "CODE_TITLE";
    public static final String TYPE_CODE_TITLE = "TYPE_CODE_TITLE";
    public static final String CODE = "CODE";
    public static final Set<String> BRANCH_FORMATS = Set.of(CODE_TITLE, TYPE_CODE_TITLE, CODE);

    /** El nombre de la rama de una tarea: su código y su título, en minúsculas y con guiones («web-22-rotacion-del-token»). */
    public static String branchName(String code, String title) {
        return branchName(CODE_TITLE, code, title, false);
    }

    /**
     * El nombre de la rama de una tarea según el formato del proyecto: código y título («web-22-rotacion-del-token»),
     * con el tipo por delante («feature/web-22-…», «fix/web-22-…» si es un bug) o solo el código («web-22»).
     */
    public static String branchName(String format, String code, String title, boolean bug) {
        String plain = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String prefix = TYPE_CODE_TITLE.equals(format) ? (bug ? "fix/" : "feature/") : "";
        String name = prefix + code.toLowerCase(Locale.ROOT) + (CODE.equals(format) || slug.isEmpty() ? "" : "-" + slug);
        return name.length() <= BRANCH_MAX ? name : name.substring(0, BRANCH_MAX).replaceAll("-+$", "");
    }

    /**
     * Si un nombre sirve como rama de git: sin espacios ni los caracteres que git no admite, sin «..», sin empezar ni
     * terminar en barra o punto.
     */
    public static boolean validBranch(String name) {
        return name != null && !name.isBlank() && name.length() <= 200
                && name.matches("[A-Za-z0-9._/-]+")
                && !name.contains("..") && !name.contains("//") && !name.endsWith(".lock")
                && !name.startsWith("/") && !name.endsWith("/") && !name.startsWith(".") && !name.endsWith(".") && !name.startsWith("-");
    }

    /**
     * Los datos que pide un workflow al ejecutarlo a mano (los {@code inputs} de {@code workflow_dispatch} en su YAML),
     * o {@code null} si el workflow no se puede ejecutar a mano. Lee solo lo que necesita de la forma habitual del
     * archivo, sin un intérprete de YAML completo.
     */
    public static List<String> dispatchInputs(String yaml) {
        String[] lines = yaml.split("\\r?\\n");
        int at = -1;
        int indent = -1;
        for (int index = 0; index < lines.length; index++) {
            String line = withoutComment(lines[index]);
            String trimmed = line.strip();
            // Solo cuenta lo que está en «on:», que va antes de los trabajos (más abajo puede nombrarse en una condición)
            if (line.startsWith("jobs:")) {
                break;
            }
            // «on: workflow_dispatch», «on: [push, workflow_dispatch]» o la clave «workflow_dispatch:»
            if (trimmed.matches("(?:['\"]?on['\"]?\\s*:\\s*)?\\[?.*\\bworkflow_dispatch\\b.*")) {
                at = index;
                indent = line.length() - line.stripLeading().length();
                if (!trimmed.matches("workflow_dispatch\\s*:.*")) {
                    return List.of();
                }
                break;
            }
        }
        if (at < 0) {
            return null;
        }
        List<String> inputs = new ArrayList<>();
        int inputsIndent = -1;
        int nameIndent = -1;
        for (int index = at + 1; index < lines.length; index++) {
            String line = withoutComment(lines[index]);
            if (line.isBlank()) {
                continue;
            }
            int depth = line.length() - line.stripLeading().length();
            if (depth <= indent) {
                break;
            }
            String trimmed = line.strip();
            if (inputsIndent < 0) {
                if (trimmed.matches("inputs\\s*:.*")) {
                    inputsIndent = depth;
                }
                continue;
            }
            if (depth <= inputsIndent) {
                break;
            }
            if (nameIndent < 0) {
                nameIndent = depth;
            }
            if (depth == nameIndent && trimmed.matches("[A-Za-z0-9_-]+\\s*:.*")) {
                inputs.add(trimmed.substring(0, trimmed.indexOf(':')).strip());
            }
        }
        return inputs;
    }

    private static String withoutComment(String line) {
        int hash = line.indexOf(" #");
        return line.strip().startsWith("#") ? "" : hash >= 0 ? line.substring(0, hash) : line;
    }
}
