package com.taskflow.api.imports.source;

import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import com.taskflow.api.storage.FileTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.taskflow.api.imports.source.SourceValues.credential;
import static com.taskflow.api.imports.source.SourceValues.encode;
import static com.taskflow.api.imports.source.SourceValues.text;

/**
 * Trello por su API REST, con la clave de API y un token de la persona (en {@link SourceConnection}, la clave va en
 * {@code email}). Cada tablero se lee completo en una sola petición (listas, tarjetas con sus adjuntos, miembros,
 * etiquetas y checklists) y lo traduce {@link TrelloBoard}; los comentarios se piden aparte, por páginas, porque son
 * acciones del tablero y no vienen con las tarjetas.
 * <p>
 * La clave y el token viajan en la consulta, que es como los documenta Trello; para descargar un adjunto solo vale la
 * cabecera OAuth.
 */
@Component
@RequiredArgsConstructor
public class TrelloSource implements ImportSource {

    private static final String API = "https://api.trello.com/1";
    private static final String LABEL = "trello.com";
    /** El tope de Trello para una página (de tarjetas o de acciones). */
    private static final int PAGE = 1000;
    private static final String CARD_FIELDS = "id,idShort,name,desc,closed,idList,idMembers,idLabels,labels,due,dueComplete,start,"
            + "dateLastActivity,idChecklists,shortUrl,pos,badges";
    /** {@code cards=all} y {@code lists=all}: sin eso no vienen las archivadas. */
    private static final String BOARD = "fields=name,desc,closed,url,shortUrl&lists=all&list_fields=name,closed,pos&cards=all&card_fields="
            + CARD_FIELDS + "&card_attachments=true&card_attachment_fields=all&members=all&member_fields=id,fullName,username"
            + "&labels=all&label_fields=name,color&labels_limit=1000&checklists=all&checklist_fields=all";

    private final SourceHttp http;

    @Override
    public ImportSourceType type() {
        return ImportSourceType.TRELLO;
    }

    @Override
    public Discovery discover(SourceConnection connection) {
        String auth = auth(connection);
        JsonNode me = http.getJson(api() + "/members/me?fields=id,username,fullName&" + auth, Map.of());
        List<Found> boards = new ArrayList<>();
        for (JsonNode board : http.getJson(api() + "/members/me/boards?filter=open&fields=id,name,closed&" + auth, Map.of())) {
            // Cuántas tarjetas tiene un tablero no se sabe sin leerlo
            if (!board.path("closed").asBoolean(false) && !text(board, "id").isEmpty()) {
                boards.add(new Found(text(board, "id"), null, text(board, "name"), -1));
            }
        }
        return new Discovery(text(me, "fullName").isBlank() ? text(me, "username") : text(me, "fullName"), LABEL, boards);
    }

    @Override
    public ImportBundle fetch(SourceConnection connection, List<String> projectIds, boolean withComments, Progress progress) {
        String auth = auth(connection);
        // Primero todos los tableros (una petición cada uno): así se sabe cuántas tarjetas hay antes de traducirlas
        Map<String, JsonNode> boards = new LinkedHashMap<>();
        int total = 0;
        for (String boardId : projectIds) {
            JsonNode board = board(boardId, auth);
            boards.put(boardId, board);
            total += board.path("cards").size();
            progress.read(0, total);
        }

        Map<String, ExtUser> users = new LinkedHashMap<>();
        List<ExtProject> projects = new ArrayList<>();
        int done = 0;
        for (Map.Entry<String, JsonNode> board : boards.entrySet()) {
            List<JsonNode> comments = withComments ? comments(board.getKey(), auth) : null;
            projects.add(TrelloBoard.project(board.getValue(), comments, true, users));
            done += board.getValue().path("cards").size();
            progress.read(done, Math.max(total, done));
        }
        return new ImportBundle(projects, List.copyOf(users.values()));
    }

    private JsonNode board(String boardId, String auth) {
        String address = api() + "/boards/" + encode(boardId);
        JsonNode board = http.getJson(address + "?" + BOARD + "&" + auth, Map.of());
        // Trello no dice si un tablero de más de mil tarjetas llega completo así: si llegó justo el tope, se piden las
        // demás por páginas, de la más nueva a la más vieja, a partir de la más vieja que ya se tiene
        if (board.path("cards") instanceof ArrayNode cards && cards.size() >= page()) {
            Set<String> seen = new HashSet<>();
            cards.forEach(card -> seen.add(text(card, "id")));
            String oldest = seen.stream().filter(id -> !id.isEmpty()).min(String::compareTo).orElse("");
            while (!oldest.isEmpty()) {
                JsonNode more = http.getJson(address + "/cards/all?limit=" + page() + "&before=" + encode(oldest) + "&fields=" + CARD_FIELDS
                        + "&attachments=true&attachment_fields=all&" + auth, Map.of());
                int added = 0;
                for (JsonNode card : more) {
                    String id = text(card, "id");
                    if (!id.isEmpty() && seen.add(id)) {
                        cards.add(card);
                        added++;
                        oldest = id.compareTo(oldest) < 0 ? id : oldest;
                    }
                }
                if (added == 0 || more.size() < page()) {
                    break;
                }
            }
        }
        return board;
    }

    /** Todos los comentarios del tablero: llegan del más nuevo al más viejo y la página siguiente empieza donde acabó esta. */
    private List<JsonNode> comments(String boardId, String auth) {
        List<JsonNode> comments = new ArrayList<>();
        String before = null;
        while (true) {
            JsonNode page = http.getJson(api() + "/boards/" + encode(boardId) + "/actions?filter=commentCard&limit=" + page()
                    + "&memberCreator_fields=fullName,username" + (before == null ? "" : "&before=" + encode(before)) + "&" + auth, Map.of());
            page.forEach(comments::add);
            String last = page.isEmpty() ? "" : text(page.get(page.size() - 1), "id");
            // Una página a medias es la última
            if (page.size() < page() || last.isEmpty() || last.equals(before)) {
                return comments;
            }
            before = last;
        }
    }

    @Override
    public byte[] download(SourceConnection connection, ExtAttachment attachment) {
        if (attachment.url() == null) {
            throw new SourceException(SourceHttp.UNREACHABLE);
        }
        // Desde 2021 un adjunto solo se descarga con esta cabecera (la clave y el token en la consulta ya no valen)
        Map<String, String> headers = fromTrello(attachment.url()) ? Map.of("Authorization", "OAuth oauth_consumer_key=\""
                + credential(connection.email()) + "\", oauth_token=\"" + credential(connection.token()) + "\"") : Map.of();
        return http.download(attachment.url(), headers, FileTypes.FILE_LIMIT);
    }

    /** La dirección del adjunto viene en la respuesta: las credenciales solo viajan si de verdad apunta a Trello. */
    private boolean fromTrello(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase(URI.create(api()).getHost())
                    || host.toLowerCase(Locale.ROOT).endsWith(".trello.com") || host.equalsIgnoreCase("trello.com"));
        } catch (IllegalArgumentException notAddress) {
            return false;
        }
    }

    /** En las pruebas la API es un servidor falso en la propia máquina. */
    String api() {
        return API;
    }

    /** En las pruebas las páginas son de dos, para no armar mil tarjetas. */
    int page() {
        return PAGE;
    }

    private static String auth(SourceConnection connection) {
        return "key=" + encode(credential(connection.email())) + "&token=" + encode(credential(connection.token()));
    }
}
