package com.taskflow.api.imports.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El conector de Trello contra un servidor falso que responde como su API REST (las formas de las respuestas son las
 * de su documentación). Las páginas son de dos elementos, para probar la paginación sin armar mil tarjetas.
 */
class TrelloSourceTest {

    private static final String BOARD = "5abbe4b7ddc1b351ef961414";
    /** Los ocho primeros dígitos son la fecha en que se creó: 1, 2 y 3 de septiembre de 2026 a las 08:00 UTC. */
    private static final String CARD_A = "6a968600aaaaaaaaaaaaaaaa";
    private static final String CARD_B = "6a97d780bbbbbbbbbbbbbbbb";
    private static final String CARD_C = "6a992900cccccccccccccccc";
    private static final String OAUTH = "OAuth oauth_consumer_key=\"clave-de-prueba\", oauth_token=\"token-de-prueba\"";

    private HttpServer server;
    private String base;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private TrelloSource source;
    private SourceConnection connection;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
        source = new TrelloSource(new SourceHttp(true)) {
            @Override
            String api() {
                return base + "/1";
            }

            @Override
            int page() {
                return 2;
            }
        };
        // En Trello la clave de API va donde otros orígenes llevan el correo
        connection = new SourceConnection(null, "clave-de-prueba", "token-de-prueba");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = new HashMap<>();
        String raw = exchange.getRequestURI().getQuery();
        for (String pair : raw == null ? new String[0] : raw.split("&")) {
            query.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        synchronized (requests) {
            requests.add(exchange.getRequestMethod() + " " + path + (raw == null ? "" : "?" + raw));
        }
        if (path.contains("/download/")) {
            // Un adjunto solo se entrega con la cabecera OAuth: la clave y el token en la consulta ya no valen
            boolean allowed = OAUTH.equals(authorization) && !query.containsKey("key") && !query.containsKey("token");
            respond(exchange, allowed ? 200 : 401, allowed ? "contenido del archivo" : "unauthorized");
            return;
        }
        // Lo demás lleva la clave y el token en la consulta, sin cabecera
        if (!"clave-de-prueba".equals(query.get("key")) || !"token-de-prueba".equals(query.get("token")) || authorization != null) {
            respond(exchange, 401, "invalid token");
            return;
        }
        String key = path;
        if (path.endsWith("/actions")) {
            key = "comentarios" + (query.containsKey("before") ? "-antes-de-" + query.get("before") : "");
        } else if (path.endsWith("/cards/all")) {
            key = "tarjetas-antes-de-" + query.get("before");
        }
        String response = bodies.get(key);
        respond(exchange, response == null ? 404 : 200, response == null ? "not found" : response);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** El tablero como lo devuelve {@code GET /1/boards/{id}} con sus listas, tarjetas, miembros, etiquetas y checklists. */
    private void board() {
        bodies.put("/1/boards/" + BOARD, """
                {"id":"%1$s","name":"Portal de clientes","desc":"Tablero del portal","closed":false,
                 "url":"https://trello.com/b/dQHqCohZ/portal-de-clientes","shortUrl":"https://trello.com/b/dQHqCohZ",
                 "lists":[
                   {"id":"l-hecho","name":"Hecho","closed":false,"pos":49152},
                   {"id":"l-por-hacer","name":"Por hacer","closed":false,"pos":16384},
                   {"id":"l-vacia","name":"Lista vieja","closed":true,"pos":81920},
                   {"id":"l-en-curso","name":"En curso","closed":false,"pos":32768},
                   {"id":"l-ideas","name":"Ideas","closed":true,"pos":65536}],
                 "labels":[{"id":"e-urgente","idBoard":"%1$s","name":"Urgente","color":"red"},
                           {"id":"e-verde","idBoard":"%1$s","name":"","color":"green"}],
                 "members":[{"id":"m-ana","fullName":"Ana Torres","username":"anatorres"},
                            {"id":"m-luis","fullName":"Luis Muñoz","username":"luismunoz"}],
                 "cards":[
                   {"id":"%3$s","idShort":3,"name":"Publicar el portal","desc":"","closed":true,"idList":"l-hecho",
                    "idMembers":[],"idLabels":[],"labels":[],"due":null,"dueComplete":false,"start":null,
                    "dateLastActivity":"2026-11-01T10:00:00.000Z","idChecklists":[],"pos":16384,
                    "shortUrl":"https://trello.com/c/cccccccc","badges":{"comments":1},
                    "attachments":[
                      {"id":"a-notas","name":"Notas finales","fileName":"notas.txt","bytes":"512","mimeType":"text/plain","isUpload":true,
                       "url":"%4$s/1/cards/%3$s/attachments/a-notas/download/notas.txt"}]},
                   {"id":"%2$s","idShort":2,"name":"Ver el historial de pedidos","desc":"Texto **Markdown**","closed":false,
                    "idList":"l-en-curso","idMembers":["m-luis","m-ana"],"idLabels":["e-urgente","e-verde"],
                    "labels":[{"id":"e-urgente","name":"Urgente","color":"red"},{"id":"e-verde","name":"","color":"green"}],
                    "due":"2026-12-01T17:00:00.000Z","dueComplete":true,"start":"2026-11-20T05:00:00.000Z",
                    "dateLastActivity":"2026-11-02T10:00:00.000Z","idChecklists":["k-pasos","k-antes"],"pos":32768,
                    "shortUrl":"https://trello.com/c/bbbbbbbb","badges":{"comments":2},
                    "attachments":[
                      {"id":"a-mockup","name":"mockup.png","bytes":2048,"mimeType":"image/png","isUpload":true,
                       "url":"%4$s/1/cards/%2$s/attachments/a-mockup/download/mockup.png"},
                      {"id":"a-enlace","name":"Diseño en Figma","bytes":null,"mimeType":null,"isUpload":false,
                       "url":"https://www.figma.com/file/abc"}]}],
                 "checklists":[
                   {"id":"k-pasos","name":"Pasos","idBoard":"%1$s","idCard":"%2$s","pos":32768,"checkItems":[
                     {"id":"i-2","idChecklist":"k-pasos","name":"Probar el filtro","state":"incomplete","pos":34000},
                     {"id":"i-1","idChecklist":"k-pasos","name":"Armar la consulta","state":"complete","pos":17000}]},
                   {"id":"k-antes","name":"Antes de empezar","idBoard":"%1$s","idCard":"%2$s","pos":16384,"checkItems":[
                     {"id":"i-0","idChecklist":"k-antes","name":"Pedir los datos","state":"complete","pos":17000}]}]}
                """.formatted(BOARD, CARD_B, CARD_C, base));
        // Llegó justo el tope de la página: lo que falta se pide desde la tarjeta más vieja que ya se tiene
        bodies.put("tarjetas-antes-de-" + CARD_B, """
                [{"id":"%s","idShort":1,"name":"Encuesta a clientes","desc":"","closed":false,"idList":"l-ideas",
                  "idMembers":["m-ana"],"idLabels":["e-urgente"],"due":null,"dueComplete":false,"start":null,
                  "shortUrl":"https://trello.com/c/aaaaaaaa","badges":{"comments":0},"attachments":[]}]
                """.formatted(CARD_A));
    }

    private static String comment(String id, String cardId, String authorId, String authorName, String text, String date) {
        return """
                {"id":"%s","idMemberCreator":"%s","type":"commentCard","date":"%s",
                 "data":{"text":"%s","card":{"id":"%s","idShort":2},"board":{"id":"%s"},"list":{"id":"l-en-curso","name":"En curso"}},
                 "memberCreator":{"id":"%s","fullName":"%s","username":"alguien"}}""".formatted(id, authorId, date, text, cardId, BOARD,
                authorId, authorName);
    }

    @Test
    void conectaYListaLosTablerosAbiertos() {
        bodies.put("/1/members/me", "{\"id\":\"m-ana\",\"username\":\"anatorres\",\"fullName\":\"Ana Torres\"}");
        bodies.put("/1/members/me/boards", """
                [{"id":"%s","name":"Portal de clientes","closed":false},
                 {"id":"5abbe4b7ddc1b351ef961999","name":"Tablero cerrado","closed":true},
                 {"id":"5abbe4b7ddc1b351ef961500","name":"Soporte interno","closed":false}]""".formatted(BOARD));

        ImportSource.Discovery discovery = source.discover(connection);

        assertThat(discovery.account()).isEqualTo("Ana Torres");
        assertThat(discovery.label()).isEqualTo("trello.com");
        // Trello no dice cuántas tarjetas tiene un tablero sin leerlo
        assertThat(discovery.projects()).containsExactly(
                new ImportSource.Found(BOARD, null, "Portal de clientes", -1),
                new ImportSource.Found("5abbe4b7ddc1b351ef961500", null, "Soporte interno", -1));
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("GET /1/members/me/boards?").contains("filter=open"));
    }

    @Test
    void unaClaveOUnTokenMaloSeLeDiceALaPersona() {
        SourceConnection wrong = new SourceConnection(null, "clave-de-prueba", "otro");

        assertThatThrownBy(() -> source.discover(wrong)).isInstanceOf(SourceException.class).hasMessage(SourceHttp.REJECTED);
    }

    @Test
    void unaCredencialVaciaOQueNoCabeEnUnaCabeceraSeRechazaSinSalirANingunaParte() {
        for (String token : new String[]{null, "  ", "con espacio", "con\nsalto", "con\"comillas"}) {
            assertThatThrownBy(() -> source.discover(new SourceConnection(null, "clave-de-prueba", token))).as(String.valueOf(token))
                    .isInstanceOf(SourceException.class).hasMessage(SourceHttp.REJECTED);
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void traeElTableroConSusListasTarjetasChecklistsAdjuntosYLosComentariosPorPaginas() {
        board();
        bodies.put("comentarios", "[" + comment("c-3", CARD_B, "m-luis", "Luis Muñoz", "Ya quedó el filtro", "2026-11-03T10:00:00.000Z") + ","
                + comment("c-2", CARD_B, "m-ana", "Ana Torres", "Revisa el paginado", "2026-11-02T10:00:00.000Z") + "]");
        // Quien comentó ya no es miembro del tablero
        bodies.put("comentarios-antes-de-c-2", "[" + comment("c-1", CARD_C, "m-marta", "Marta Ríos", "Listo para publicar", "2026-11-01T10:00:00.000Z") + "]");
        List<String> progress = new ArrayList<>();

        ImportBundle bundle = source.fetch(connection, List.of(BOARD), true, (done, total) -> progress.add(done + "/" + total));

        assertThat(progress).containsExactly("0/3", "3/3");
        ExtProject project = bundle.projects().get(0);
        assertThat(project.id()).isEqualTo(BOARD);
        assertThat(project.key()).isNull();
        assertThat(project.name()).isEqualTo("Portal de clientes");
        assertThat(project.description()).isEqualTo("Tablero del portal");
        assertThat(project.url()).isEqualTo("https://trello.com/b/dQHqCohZ/portal-de-clientes");
        // Las listas, en el orden del tablero; una archivada solo si le quedaron tarjetas
        assertThat(project.statuses()).extracting(status -> status.name() + " " + status.category())
                .containsExactly("Por hacer TODO", "En curso IN_PROGRESS", "Hecho DONE", "Ideas BACKLOG");
        assertThat(project.sprints()).isEmpty();
        assertThat(project.modules()).isEmpty();
        // De la más vieja a la más nueva, con la que llegó en la segunda página
        assertThat(project.issues()).extracting(ExtIssue::title)
                .containsExactly("Encuesta a clientes", "Ver el historial de pedidos", "Publicar el portal");

        ExtIssue idea = project.issues().get(0);
        assertThat(idea.number()).isEqualTo(1);
        assertThat(idea.status()).isEqualTo("Ideas");
        assertThat(idea.statusCategory()).isEqualTo(StatusCategory.BACKLOG);
        // Está en una lista archivada
        assertThat(idea.archived()).isTrue();
        // Sin `labels`, el nombre sale de las etiquetas del tablero
        assertThat(idea.labels()).containsExactly("Urgente");
        assertThat(idea.createdAt()).isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
        assertThat(idea.comments()).isEmpty();

        ExtIssue card = project.issues().get(1);
        assertThat(card.id()).isEqualTo(CARD_B);
        assertThat(card.key()).isNull();
        assertThat(card.number()).isEqualTo(2);
        assertThat(card.type()).isNull();
        assertThat(card.kind()).isEqualTo(Kind.TASK);
        assertThat(card.description()).isEqualTo("Texto **Markdown**");
        assertThat(card.status()).isEqualTo("En curso");
        // La fecha se marcó como cumplida, pero la tarjeta sigue en su lista
        assertThat(card.statusCategory()).isEqualTo(StatusCategory.IN_PROGRESS);
        assertThat(card.completedAt()).isEqualTo(Instant.parse("2026-12-01T17:00:00Z"));
        assertThat(card.dueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(card.startDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(card.createdAt()).isEqualTo(Instant.parse("2026-09-02T08:00:00Z"));
        assertThat(card.archived()).isFalse();
        assertThat(card.priority()).isNull();
        assertThat(card.assigneeIds()).containsExactly("m-luis", "m-ana");
        // Una etiqueta sin nombre se conoce por su color
        assertThat(card.labels()).containsExactly("Urgente", "green");
        assertThat(card.url()).isEqualTo("https://trello.com/c/bbbbbbbb");
        // Las checklists y sus ítems, en el orden en que se ven
        assertThat(card.checklist()).extracting(item -> item.text() + " " + item.done())
                .containsExactly("Pedir los datos true", "Armar la consulta true", "Probar el filtro false");
        // El enlace a Figma no es un archivo
        assertThat(card.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.id()).isEqualTo("a-mockup");
            assertThat(attachment.name()).isEqualTo("mockup.png");
            assertThat(attachment.size()).isEqualTo(2048);
            assertThat(attachment.contentType()).isEqualTo("image/png");
            assertThat(attachment.url()).isEqualTo(base + "/1/cards/" + CARD_B + "/attachments/a-mockup/download/mockup.png");
        });
        // Las acciones llegan de la más nueva a la más vieja
        assertThat(card.commentCount()).isEqualTo(2);
        assertThat(card.comments()).extracting(comment -> comment.authorId() + ": " + comment.body() + " " + comment.createdAt())
                .containsExactly("m-ana: Revisa el paginado 2026-11-02T10:00:00Z", "m-luis: Ya quedó el filtro 2026-11-03T10:00:00Z");

        ExtIssue published = project.issues().get(2);
        assertThat(published.archived()).isTrue();
        assertThat(published.statusCategory()).isEqualTo(StatusCategory.DONE);
        assertThat(published.completedAt()).isNull();
        assertThat(published.dueDate()).isNull();
        assertThat(published.checklist()).isEmpty();
        // Al renombrarlo perdió la extensión: se guarda con la del archivo que se subió. El tamaño llegó como texto
        assertThat(published.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.name()).isEqualTo("notas.txt");
            assertThat(attachment.size()).isEqualTo(512);
        });
        // El comentario de la segunda página
        assertThat(published.comments()).singleElement().satisfies(comment -> {
            assertThat(comment.id()).isEqualTo("c-1");
            assertThat(comment.authorName()).isEqualTo("Marta Ríos");
        });

        // Los miembros del tablero y quien comentó sin serlo, una vez cada uno y sin correo
        assertThat(bundle.users()).extracting(user -> user.id() + " " + user.name() + " " + user.email())
                .containsExactly("m-ana Ana Torres null", "m-luis Luis Muñoz null", "m-marta Marta Ríos null");
        // El tablero se pide con las archivadas, y cada página siguiente desde donde acabó la anterior
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("GET /1/boards/" + BOARD + "?")
                .contains("cards=all").contains("lists=all").contains("card_attachments=true").contains("checklists=all"));
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("GET /1/boards/" + BOARD + "/cards/all?")
                .contains("limit=2").contains("before=" + CARD_B));
        assertThat(requests).filteredOn(request -> request.contains("/actions?")).hasSize(2)
                .allSatisfy(request -> assertThat(request).contains("filter=commentCard").contains("limit=2"))
                .anySatisfy(request -> assertThat(request).contains("before=c-2"));
    }

    @Test
    void sinComentariosNoPideLasAccionesPeroDiceCuantosTieneCadaTarjeta() {
        board();

        ImportBundle bundle = source.fetch(connection, List.of(BOARD), false, (done, total) -> {
        });

        ExtIssue card = bundle.projects().get(0).issues().get(1);
        assertThat(card.comments()).isEmpty();
        assertThat(card.commentCount()).isEqualTo(2);
        assertThat(requests).noneMatch(request -> request.contains("/actions"));
    }

    @Test
    void unTableroQueNoExisteSeLeDiceALaPersona() {
        assertThatThrownBy(() -> source.fetch(connection, List.of("no-existe"), true, (done, total) -> {
        })).isInstanceOf(SourceException.class).hasMessage(SourceHttp.NOT_FOUND);
    }

    @Test
    void descargaUnAdjuntoConLaCabeceraOAuthYSinLaClaveEnLaDireccion() {
        String url = base + "/1/cards/" + CARD_B + "/attachments/a-mockup/download/mockup.png";

        byte[] content = source.download(connection, new ImportBundle.ExtAttachment("a-mockup", "mockup.png", 21, "image/png", url));

        assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("contenido del archivo");
        assertThat(requests).containsExactly("GET /1/cards/" + CARD_B + "/attachments/a-mockup/download/mockup.png");
    }
}
