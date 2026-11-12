package com.taskflow.api.imports.source;

import com.taskflow.api.imports.csv.CsvImport;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;
import com.taskflow.api.imports.model.ImportBundle.ExtChecklistItem;
import com.taskflow.api.imports.model.ImportBundle.ExtComment;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtStatus;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import com.taskflow.api.storage.FileTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.taskflow.api.imports.source.SourceValues.blankToNull;
import static com.taskflow.api.imports.source.SourceValues.date;
import static com.taskflow.api.imports.source.SourceValues.instant;
import static com.taskflow.api.imports.source.SourceValues.text;

/**
 * El JSON de un tablero de Trello, en el formato común. La API ({@code GET /1/boards/{id}} con sus listas, tarjetas,
 * miembros y checklists) y el archivo que exporta el tablero traen la misma forma, así que {@link TrelloSource} y
 * {@link TrelloFile} comparten este traductor.
 * <p>
 * Un tablero es un proyecto, sus listas son los estados y sus tarjetas, las tareas. Trello no tiene tipos, prioridades
 * ni sprints.
 */
final class TrelloBoard {

    /** El estado de una tarjeta cuya lista no viene en el tablero (no debería pasar; así no se pierde la tarjeta). */
    static final String NO_LIST = "Unknown";

    private static final String COMMENT = "commentCard";

    private TrelloBoard() {
    }

    /**
     * @param actions         las acciones de donde salen los comentarios (las que no son comentarios se ignoran);
     *                        {@code null} si no se pidieron
     * @param withAttachments {@code false} con el archivo: nombra los adjuntos pero no da con qué descargarlos
     * @param users           donde se anota a cada persona, una sola vez
     */
    static ExtProject project(JsonNode board, Iterable<JsonNode> actions, boolean withAttachments, Map<String, ExtUser> users) {
        board.path("members").forEach(member -> person(users, member));

        Map<String, JsonNode> lists = new LinkedHashMap<>();
        for (JsonNode list : byPosition(board.path("lists"))) {
            lists.put(text(list, "id"), list);
        }
        Map<String, JsonNode> boardLabels = new HashMap<>();
        board.path("labels").forEach(label -> boardLabels.put(text(label, "id"), label));
        Map<String, List<JsonNode>> checklists = new HashMap<>();
        for (JsonNode checklist : board.path("checklists")) {
            checklists.computeIfAbsent(text(checklist, "idCard"), card -> new ArrayList<>()).add(checklist);
        }
        Map<String, List<ExtComment>> comments = comments(actions, users);

        // En el orden en que se crearon: el identificador empieza por su fecha
        List<JsonNode> cards = new ArrayList<>();
        board.path("cards").forEach(cards::add);
        cards.sort(Comparator.comparing(card -> text(card, "id")));

        Set<String> usedLists = new HashSet<>();
        List<ExtIssue> issues = new ArrayList<>();
        boolean orphans = false;
        for (JsonNode card : cards) {
            String id = text(card, "id");
            JsonNode list = lists.get(text(card, "idList"));
            usedLists.add(text(card, "idList"));
            orphans |= list == null;
            String status = list == null ? NO_LIST : name(list);

            Set<String> labels = new LinkedHashSet<>();
            JsonNode cardLabels = card.path("labels");
            if (cardLabels.isArray()) {
                cardLabels.forEach(label -> labels.add(label(label)));
            } else {
                card.path("idLabels").forEach(labelId -> labels.add(label(boardLabels.getOrDefault(labelId.asString(""), labelId))));
            }
            labels.remove("");

            List<String> assignees = new ArrayList<>();
            card.path("idMembers").forEach(member -> assignees.add(member.asString("")));
            assignees.remove("");

            // Marcar la fecha como cumplida no mueve la tarjeta de lista: queda anotado cuándo, y el estado no cambia
            Instant due = instant(text(card, "due"));
            Instant completedAt = !card.path("dueComplete").asBoolean(false) ? null
                    : due != null ? due : instant(text(card, "dateLastActivity"));

            List<ExtChecklistItem> checklist = new ArrayList<>();
            for (JsonNode group : byPosition(checklists.getOrDefault(id, List.of()))) {
                for (JsonNode item : byPosition(group.path("checkItems"))) {
                    checklist.add(new ExtChecklistItem(text(item, "name"), "complete".equals(text(item, "state"))));
                }
            }

            List<ExtAttachment> attachments = new ArrayList<>();
            if (withAttachments) {
                for (JsonNode attachment : card.path("attachments")) {
                    // Los que no se subieron a Trello son enlaces a otra parte
                    if (attachment.path("isUpload").asBoolean(false)) {
                        attachments.add(new ExtAttachment(text(attachment, "id"), fileName(attachment), bytes(attachment.path("bytes")),
                                blankToNull(text(attachment, "mimeType")), blankToNull(text(attachment, "url"))));
                    }
                }
            }

            List<ExtComment> own = comments.getOrDefault(id, List.of());
            JsonNode number = card.path("idShort");
            issues.add(new ExtIssue(id, null, number.isNumber() ? Integer.valueOf((int) number.asDouble()) : null, null, Kind.TASK,
                    text(card, "name"), text(card, "desc"), status, category(status), null, assignees, null, List.copyOf(labels),
                    null, List.of(), List.of(), date(text(card, "start")), date(text(card, "due")), created(id), completedAt, null,
                    null, Map.of(), checklist, own, actions == null ? card.path("badges").path("comments").asInt(0) : own.size(),
                    attachments, List.of(), blankToNull(text(card, "shortUrl").isEmpty() ? text(card, "url") : text(card, "shortUrl")),
                    // Las tarjetas de una lista archivada tampoco se ven en el tablero
                    card.path("closed").asBoolean(false) || (list != null && list.path("closed").asBoolean(false))));
        }

        Map<String, StatusCategory> statuses = new LinkedHashMap<>();
        for (JsonNode list : lists.values()) {
            // Una lista archivada solo cuenta si quedaron tarjetas en ella
            if (!list.path("closed").asBoolean(false) || usedLists.contains(text(list, "id"))) {
                statuses.putIfAbsent(name(list), category(name(list)));
            }
        }
        if (orphans) {
            statuses.putIfAbsent(NO_LIST, category(NO_LIST));
        }
        List<ExtStatus> states = new ArrayList<>();
        statuses.forEach((name, category) -> states.add(new ExtStatus(name, category)));

        String id = text(board, "id");
        return new ExtProject(id.isEmpty() ? "trello" : id, null, text(board, "name"), blankToNull(text(board, "desc")),
                blankToNull(text(board, "url").isEmpty() ? text(board, "shortUrl") : text(board, "url")), states, List.of(),
                List.of(), issues);
    }

    /** Los comentarios de cada tarjeta, del más viejo al más nuevo (las acciones llegan al revés). */
    private static Map<String, List<ExtComment>> comments(Iterable<JsonNode> actions, Map<String, ExtUser> users) {
        Map<String, List<ExtComment>> comments = new HashMap<>();
        if (actions == null) {
            return comments;
        }
        for (JsonNode action : actions) {
            if (!COMMENT.equals(text(action, "type"))) {
                continue;
            }
            // Quien comentó puede haber salido ya del tablero: se anota con lo que traiga la acción
            JsonNode author = action.path("memberCreator");
            String authorId = author.isObject() && !text(author, "id").isEmpty() ? person(users, author)
                    : blankToNull(text(action, "idMemberCreator"));
            comments.computeIfAbsent(text(action.path("data").path("card"), "id"), card -> new ArrayList<>())
                    .add(new ExtComment(text(action, "id"), authorId, blankToNull(text(author, "fullName")),
                            text(action.path("data"), "text"), instant(text(action, "date"))));
        }
        comments.values().forEach(list -> list.sort(Comparator.comparing(ExtComment::createdAt,
                Comparator.nullsFirst(Comparator.naturalOrder()))));
        return comments;
    }

    /** Anota a la persona y devuelve su identificador. Trello no da el correo de los demás miembros. */
    private static String person(Map<String, ExtUser> users, JsonNode member) {
        String id = text(member, "id");
        if (id.isEmpty()) {
            return null;
        }
        String name = text(member, "fullName").isBlank() ? text(member, "username") : text(member, "fullName");
        users.putIfAbsent(id, new ExtUser(id, name.isBlank() ? id : name, null));
        return id;
    }

    private static String name(JsonNode list) {
        return text(list, "name").isBlank() ? NO_LIST : text(list, "name");
    }

    private static StatusCategory category(String list) {
        return CsvImport.category("", list, false);
    }

    /** Una etiqueta sin nombre se conoce por su color. */
    private static String label(JsonNode label) {
        return text(label, "name").isBlank() ? text(label, "color") : text(label, "name");
    }

    /**
     * Con qué nombre se guarda: el que se ve en la tarjeta o, si a ese le quitaron la extensión al renombrarlo (y sin
     * ella no se sabe qué tipo de archivo es), el del archivo que se subió.
     */
    private static String fileName(JsonNode attachment) {
        String name = text(attachment, "name");
        String original = text(attachment, "fileName");
        return name.isBlank() || (FileTypes.byExtension(name).isEmpty() && FileTypes.byExtension(original).isPresent())
                ? (original.isBlank() ? name : original) : name;
    }

    /** El tamaño llega como número o como texto, según de dónde se lea. */
    private static long bytes(JsonNode bytes) {
        if (bytes.isNumber()) {
            return (long) bytes.asDouble();
        }
        try {
            return bytes.isString() ? Long.parseLong(bytes.stringValue().strip()) : 0;
        } catch (NumberFormatException notNumber) {
            return 0;
        }
    }

    /** Trello no guarda cuándo se creó una tarjeta: va en los ocho primeros dígitos de su identificador (en segundos). */
    private static Instant created(String id) {
        if (id.length() < 8) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(id.substring(0, 8), 16));
        } catch (NumberFormatException notHex) {
            return null;
        }
    }

    /** En el orden en que se ven ({@code pos}); lo que no lo traiga queda como llegó. */
    private static List<JsonNode> byPosition(Iterable<JsonNode> nodes) {
        List<JsonNode> sorted = new ArrayList<>();
        nodes.forEach(sorted::add);
        sorted.sort(Comparator.comparingDouble(node -> node.path("pos").isNumber() ? node.path("pos").asDouble() : Double.MAX_VALUE));
        return sorted;
    }
}
