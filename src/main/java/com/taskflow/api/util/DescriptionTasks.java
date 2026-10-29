package com.taskflow.api.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Casillas («- [ ] …») de una descripción en Markdown, para el historial: marcar o desmarcar una casilla deja
 * «marcó «…» en la descripción» en lugar de «editó la descripción».
 * <p>
 * Reconoce las casillas como las escribe el editor (y como las lee GFM): una viñeta {@code -}, {@code *},
 * {@code +} o un número ({@code 1.}, {@code 1)}), un espacio y {@code [ ]} o {@code [x]}, también anidadas y dentro
 * de citas. Las líneas dentro de un bloque de código no son casillas.
 */
public final class DescriptionTasks {

    /** Largo máximo del texto de una casilla en el historial. */
    static final int MAX_TEXT = 300;

    /** Línea de una casilla: {@code group(1)} hasta el corchete, {@code group(2)} la marca, {@code group(4)} el texto. */
    private static final Pattern TASK = Pattern.compile(
            "^((?:[ \\t]*>[ \\t]?)*[ \\t]*(?:[-*+]|\\d{1,9}[.)])[ \\t]+\\[)([ xX])(\\](?:[ \\t]+(.*))?)$");

    /** Inicio o fin de un bloque de código ({@code ```} o {@code ~~~}, también dentro de una cita). */
    private static final Pattern FENCE = Pattern.compile("^(?:[ \\t]*>[ \\t]?)*[ \\t]{0,3}(`{3,}|~{3,})(.*)$");

    private DescriptionTasks() {
    }

    /** Una casilla de la descripción: su texto (el Markdown de la línea, sin la viñeta) y si está marcada. */
    private record TaskItem(String text, boolean checked) {
    }

    /** Una casilla que se marcó ({@code checked}) o se desmarcó. */
    public record Toggle(String text, boolean checked) {
    }

    /**
     * Qué cambió en las casillas entre dos versiones de la descripción.
     *
     * @param onlyToggles el texto es el mismo salvo las marcas de algunas casillas (no hubo otra edición)
     * @param toggles     las casillas que cambiaron de marca, en el orden del documento
     */
    public record Diff(boolean onlyToggles, List<Toggle> toggles) {
    }

    /** Compara dos versiones de la descripción ({@code null} = sin descripción). */
    public static Diff diff(String before, String after) {
        Parsed old = parse(before);
        Parsed current = parse(after);
        if (old.normalized().equals(current.normalized())) {
            // Mismo texto salvo las marcas: las casillas son las mismas y en el mismo orden
            List<Toggle> toggles = new ArrayList<>();
            for (int i = 0; i < current.items().size(); i++) {
                TaskItem now = current.items().get(i);
                if (now.checked() != old.items().get(i).checked()) {
                    toggles.add(new Toggle(now.text(), now.checked()));
                }
            }
            return new Diff(!toggles.isEmpty(), toggles.stream().filter(toggle -> !toggle.text().isEmpty()).toList());
        }
        // Hubo otra edición: las casillas se emparejan por texto (la primera «X» de antes con la primera de ahora)
        Map<String, Deque<TaskItem>> previous = new HashMap<>();
        old.items().forEach(item -> previous.computeIfAbsent(item.text(), text -> new ArrayDeque<>()).add(item));
        List<Toggle> toggles = new ArrayList<>();
        for (TaskItem now : current.items()) {
            Deque<TaskItem> same = previous.get(now.text());
            TaskItem was = same == null ? null : same.poll();
            if (was != null && was.checked() != now.checked() && !now.text().isEmpty()) {
                toggles.add(new Toggle(now.text(), now.checked()));
            }
        }
        return new Diff(false, toggles);
    }

    /** La descripción con todas las casillas sin marcar, y sus casillas. */
    private record Parsed(String normalized, List<TaskItem> items) {
    }

    private static Parsed parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new Parsed("", List.of());
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<TaskItem> items = new ArrayList<>();
        StringBuilder normalized = new StringBuilder();
        String fence = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                normalized.append('\n');
            }
            Matcher fenceMatch = FENCE.matcher(line);
            if (fenceMatch.matches()) {
                String marker = fenceMatch.group(1);
                if (fence == null) {
                    fence = marker;
                } else if (marker.charAt(0) == fence.charAt(0) && marker.length() >= fence.length()
                        && fenceMatch.group(2).isBlank()) {
                    fence = null;
                }
                normalized.append(line);
                continue;
            }
            Matcher task = fence == null ? TASK.matcher(line) : null;
            if (task != null && task.matches()) {
                String text = task.group(4) == null ? "" : task.group(4).strip();
                items.add(new TaskItem(shorten(text), !task.group(2).equals(" ")));
                normalized.append(task.group(1)).append(' ').append(task.group(3));
            } else {
                normalized.append(line);
            }
        }
        return new Parsed(normalized.toString(), items);
    }

    private static String shorten(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1).stripTrailing() + "…";
    }
}
