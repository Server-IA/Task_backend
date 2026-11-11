package com.taskflow.api.imports.text;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Pasa a Markdown el texto con formato de Jira Cloud (Atlassian Document Format: un árbol JSON de nodos «paragraph»,
 * «heading», «bulletList»…), que es como llegan las descripciones y los comentarios por su API. Lo que Markdown no
 * tiene (colores, paneles, estados) queda como texto; un nodo desconocido aporta el texto que lleve dentro, para no
 * perder contenido si Jira agrega tipos nuevos.
 */
public final class AdfToMarkdown {

    private AdfToMarkdown() {
    }

    /** @return el Markdown, o cadena vacía si el documento falta o no tiene contenido */
    public static String convert(JsonNode document) {
        if (document == null || document.isNull() || document.isMissingNode()) {
            return "";
        }
        if (document.isString()) {
            return document.stringValue().strip();
        }
        return blocks(document.path("content"), "").strip();
    }

    /** Varios bloques seguidos, separados por una línea en blanco; `indent` antecede a cada línea (listas anidadas). */
    private static String blocks(JsonNode nodes, String indent) {
        List<String> parts = new ArrayList<>();
        for (JsonNode node : nodes) {
            String text = block(node);
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        String joined = String.join("\n\n", parts);
        return indent.isEmpty() ? joined : joined.replace("\n", "\n" + indent);
    }

    private static String block(JsonNode node) {
        JsonNode content = node.path("content");
        JsonNode attrs = node.path("attrs");
        return switch (text(node, "type")) {
            case "paragraph" -> inline(content);
            case "heading" -> "#".repeat(Math.clamp(attrs.path("level").asInt(1), 1, 6)) + " " + inline(content);
            case "bulletList" -> list(content, false, 1);
            case "orderedList" -> list(content, true, attrs.path("order").asInt(1));
            case "taskList" -> taskList(content);
            case "decisionList" -> list(content, false, 1);
            case "codeBlock" -> "```" + text(attrs, "language") + "\n" + plain(content) + "\n```";
            case "blockquote" -> quote(blocks(content, ""));
            case "panel" -> quote(blocks(content, ""));
            case "expand", "nestedExpand" -> {
                String title = text(attrs, "title");
                yield (title.isBlank() ? "" : "**" + title + "**\n\n") + blocks(content, "");
            }
            case "rule" -> "---";
            case "table" -> table(content);
            case "mediaSingle", "mediaGroup" -> media(content);
            case "blockCard", "embedCard" -> link(text(attrs, "url"));
            // Un nodo en línea fuera de un párrafo, o un tipo que no conocemos: lo que lleve dentro
            default -> content.isEmpty() ? inlineNode(node) : blocks(content, "");
        };
    }

    private static String list(JsonNode items, boolean ordered, int start) {
        StringBuilder out = new StringBuilder();
        int number = start;
        for (JsonNode item : items) {
            String marker = ordered ? number++ + ". " : "- ";
            append(out, marker, itemBody(item.path("content"), " ".repeat(marker.length())));
        }
        return out.toString();
    }

    /** El contenido de un elemento de lista: una lista anidada va pegada a su párrafo (sin línea en blanco entre los dos). */
    private static String itemBody(JsonNode nodes, String indent) {
        StringBuilder out = new StringBuilder();
        for (JsonNode node : nodes) {
            String text = block(node);
            if (text.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(text(node, "type").endsWith("List") ? "\n" : "\n\n");
            }
            out.append(text);
        }
        return out.toString().replace("\n", "\n" + indent);
    }

    private static String taskList(JsonNode items) {
        StringBuilder out = new StringBuilder();
        for (JsonNode item : items) {
            if ("taskList".equals(text(item, "type"))) {
                // Una lista de tareas dentro de otra
                append(out, "  ", taskList(item.path("content")).replace("\n", "\n  "));
                continue;
            }
            boolean done = "DONE".equals(text(item.path("attrs"), "state"));
            append(out, done ? "- [x] " : "- [ ] ", inline(item.path("content")));
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String marker, String body) {
        if (!out.isEmpty()) {
            out.append('\n');
        }
        out.append(marker).append(body);
    }

    private static String quote(String text) {
        return "> " + text.replace("\n", "\n> ");
    }

    private static String table(JsonNode rows) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (JsonNode row : rows) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row.path("content")) {
                // Una celda es de una sola línea en Markdown
                cells.add(blocks(cell.path("content"), "").replace("|", "\\|").replaceAll("\\s*\\n+\\s*", " "));
            }
            if (cells.isEmpty()) {
                continue;
            }
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (index++ == 0) {
                out.append("|").append(" --- |".repeat(cells.size())).append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    /** Las imágenes y archivos van aparte, como adjuntos de la tarea: aquí queda su nombre, si lo tienen. */
    private static String media(JsonNode items) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode attrs = item.path("attrs");
            String url = text(attrs, "url");
            String alt = text(attrs, "alt");
            if (!url.isBlank()) {
                names.add("![" + alt + "](" + url + ")");
            } else if (!alt.isBlank()) {
                names.add("*(adjunto: " + alt + ")*");
            }
        }
        return String.join(" ", names);
    }

    private static String inline(JsonNode nodes) {
        StringBuilder out = new StringBuilder();
        for (JsonNode node : nodes) {
            out.append(inlineNode(node));
        }
        return out.toString().strip();
    }

    private static String inlineNode(JsonNode node) {
        JsonNode attrs = node.path("attrs");
        return switch (text(node, "type")) {
            case "text" -> marked(text(node, "text"), node.path("marks"));
            case "hardBreak" -> "  \n";
            case "mention" -> {
                String name = text(attrs, "text");
                yield name.startsWith("@") ? name : "@" + name;
            }
            case "emoji" -> {
                String glyph = text(attrs, "text");
                yield glyph.isBlank() ? text(attrs, "shortName") : glyph;
            }
            case "inlineCard" -> link(text(attrs, "url"));
            case "status" -> "`" + text(attrs, "text") + "`";
            case "date" -> date(text(attrs, "timestamp"));
            case "placeholder" -> "";
            default -> node.path("content").isEmpty() ? text(node, "text") : inline(node.path("content"));
        };
    }

    private static String marked(String text, JsonNode marks) {
        if (text.isEmpty()) {
            return text;
        }
        String href = null;
        boolean code = false;
        boolean strong = false;
        boolean em = false;
        boolean strike = false;
        for (JsonNode mark : marks) {
            switch (text(mark, "type")) {
                case "code" -> code = true;
                case "strong" -> strong = true;
                case "em" -> em = true;
                case "strike" -> strike = true;
                case "link" -> href = text(mark.path("attrs"), "href");
                default -> {
                    // Subrayado, color, subíndice: Markdown no los tiene
                }
            }
        }
        // Los marcadores van pegados al texto: «** negrita **» no es negrita
        String lead = text.substring(0, text.length() - text.stripLeading().length());
        String trail = text.substring(text.stripTrailing().length());
        String core = text.strip();
        if (core.isEmpty()) {
            return text;
        }
        if (code) {
            core = "`" + core + "`";
        } else {
            if (strong) {
                core = "**" + core + "**";
            }
            if (em) {
                core = "*" + core + "*";
            }
            if (strike) {
                core = "~~" + core + "~~";
            }
        }
        if (href != null && !href.isBlank()) {
            core = "[" + core + "](" + href + ")";
        }
        return lead + core + trail;
    }

    private static String link(String url) {
        return url.isBlank() ? "" : "<" + url + ">";
    }

    private static String date(String timestamp) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(timestamp)).atOffset(ZoneOffset.UTC).toLocalDate().toString();
        } catch (NumberFormatException notMillis) {
            return timestamp;
        }
    }

    /** El texto de un bloque de código: sus nodos de texto, sin formato. */
    private static String plain(JsonNode nodes) {
        StringBuilder out = new StringBuilder();
        for (JsonNode node : nodes) {
            out.append(text(node, "text"));
        }
        return out.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isString()) {
            return value.stringValue();
        }
        return value.isNumber() || value.isBoolean() ? value.asString() : "";
    }
}
