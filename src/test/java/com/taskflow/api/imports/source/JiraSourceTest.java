package com.taskflow.api.imports.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.LinkType;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El conector de Jira contra un servidor falso que responde como la API REST v3 de Jira Cloud (las formas de las
 * respuestas son las de su documentación).
 */
class JiraSourceTest {

    private HttpServer server;
    private String base;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private JiraSource source;
    private SourceConnection connection;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
        source = new JiraSource(new SourceHttp(true)) {
            @Override
            boolean allowed(URI uri, String host) {
                return true;
            }
        };
        connection = new SourceConnection(base, "ana@miempresa.com", "token-de-prueba");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (requests) {
            requests.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + (body.isEmpty() ? "" : " " + body));
        }
        String expected = "Basic " + Base64.getEncoder().encodeToString("ana@miempresa.com:token-de-prueba".getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization")) && !path.startsWith("/descarga")) {
            respond(exchange, 401, "{}");
            return;
        }
        if (path.equals("/rest/api/3/attachment/content/501")) {
            exchange.getResponseHeaders().add("Location", base + "/descarga/501");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
            return;
        }
        if (path.equals("/descarga/501")) {
            // La descarga redirigida ya no lleva las credenciales... al mismo servidor sí (es el mismo host)
            respond(exchange, 200, "contenido del archivo");
            return;
        }
        String key = path;
        if (path.equals("/rest/api/3/search/jql")) {
            key = body.contains("\"nextPageToken\":\"pagina-2\"") ? "search-2" : "search-1";
        } else if (path.equals("/rest/api/3/issue/10003/comment")) {
            key = "comments-" + (query.contains("startAt=0") ? "0" : "1");
        }
        String response = bodies.get(key);
        respond(exchange, response == null ? 404 : 200, response == null ? "{}" : response);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String adf(String text) {
        return "{\"type\":\"doc\",\"version\":1,\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]}";
    }

    @Test
    void conectaYListaLosProyectosConSuCantidadDeIncidencias() {
        bodies.put("/rest/api/3/myself", "{\"accountId\":\"u-ana\",\"displayName\":\"Ana Torres\",\"emailAddress\":\"ana@miempresa.com\"}");
        bodies.put("/rest/api/3/project/search", """
                {"isLast":true,"values":[
                  {"id":"10000","key":"PORT","name":"Portal de clientes","insight":{"totalIssueCount":248}},
                  {"id":"10001","key":"SUP","name":"Soporte interno"}]}""");
        bodies.put("/rest/api/3/search/approximate-count", "{\"count\":57}");

        ImportSource.Discovery discovery = source.discover(connection);

        assertThat(discovery.account()).isEqualTo("Ana Torres");
        assertThat(discovery.label()).isEqualTo("127.0.0.1");
        assertThat(discovery.projects()).containsExactly(
                new ImportSource.Found("10000", "PORT", "Portal de clientes", 248),
                new ImportSource.Found("10001", "SUP", "Soporte interno", 57));
    }

    @Test
    void unTokenMaloSeLeDiceALaPersona() {
        SourceConnection wrong = new SourceConnection(base, "ana@miempresa.com", "otro");

        assertThatThrownBy(() -> source.discover(wrong)).isInstanceOf(SourceException.class).hasMessage(SourceHttp.REJECTED);
    }

    @Test
    void soloAceptaSitiosDeAtlassian() {
        JiraSource real = new JiraSource(new SourceHttp());

        assertThat(real.site(new SourceConnection("miempresa.atlassian.net/jira/software", null, null))).isEqualTo("https://miempresa.atlassian.net");
        for (String address : List.of("http://miempresa.atlassian.net", "https://localhost:8080", "https://atlassian.net.malo.com", "", "https://10.0.0.5")) {
            assertThatThrownBy(() -> real.site(new SourceConnection(address, null, null))).as(address)
                    .isInstanceOf(SourceException.class).hasMessage(JiraSource.BAD_SITE);
        }
    }

    @Test
    void traeLasIncidenciasPorPaginasConSuJerarquiaSprintsComentariosAdjuntosYEnlaces() {
        bodies.put("/rest/api/3/field", """
                [{"id":"summary","name":"Summary","schema":{"type":"string","system":"summary"}},
                 {"id":"customfield_10020","name":"Sprint","schema":{"type":"array","custom":"com.pyxis.greenhopper.jira:gh-sprint"}},
                 {"id":"customfield_10016","name":"Story point estimate","schema":{"type":"number","custom":"com.pyxis.greenhopper.jira:jsw-story-points"}}]""");
        bodies.put("/rest/api/3/project/10000", "{\"id\":\"10000\",\"key\":\"PORT\",\"name\":\"Portal de clientes\",\"description\":\"\"}");
        bodies.put("/rest/api/3/search/approximate-count", "{\"count\":3}");
        bodies.put("search-1", """
                {"isLast":false,"nextPageToken":"pagina-2","issues":[
                  {"id":"10001","key":"PORT-1","fields":{"summary":"Pedidos","issuetype":{"name":"Epic","subtask":false,"hierarchyLevel":1},
                    "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}},"created":"2026-09-01T08:00:00.000+0000",
                    "labels":[],"components":[],"attachment":[],"issuelinks":[],"comment":{"comments":[],"total":0}}},
                  {"id":"10002","key":"PORT-2","fields":{"summary":"Ver el historial de pedidos","description":%s,
                    "issuetype":{"name":"Story","subtask":false,"hierarchyLevel":0},
                    "status":{"name":"In QA","statusCategory":{"key":"indeterminate"}},"priority":{"name":"High"},
                    "assignee":{"accountId":"u-luis","displayName":"Luis Muñoz","emailAddress":"luis@miempresa.com"},
                    "reporter":{"accountId":"u-ana","displayName":"Ana Torres"},
                    "labels":["backend"],"components":[{"id":"1","name":"Pedidos"}],"parent":{"id":"10001","key":"PORT-1"},
                    "created":"2026-09-02T10:00:00.000-0500","duedate":"2026-10-15",
                    "customfield_10020":[{"id":7,"name":"Sprint 1","state":"closed","startDate":"2026-09-01T09:00:00.000Z","endDate":"2026-09-14T09:00:00.000Z","completeDate":"2026-09-14T17:30:00.000Z"},
                                         {"id":8,"name":"Sprint 2","state":"active","startDate":"2026-09-15T09:00:00.000Z","endDate":"2026-09-28T09:00:00.000Z"}],
                    "customfield_10016":5.0,"timetracking":{"timeSpentSeconds":12000},
                    "attachment":[{"id":"501","filename":"mockup.png","size":2048,"mimeType":"image/png"}],
                    "issuelinks":[{"type":{"name":"Blocks","inward":"is blocked by","outward":"blocks"},"outwardIssue":{"id":"10003","key":"PORT-3"}}],
                    "comment":{"comments":[{"id":"c1","author":{"accountId":"u-ana","displayName":"Ana Torres"},"body":%s,"created":"2026-09-03T12:00:00.000+0000"}],"total":1}}}]}
                """.formatted(adf("Como cliente quiero ver mis pedidos"), adf("Revisa el paginado")));
        bodies.put("search-2", """
                {"issues":[
                  {"id":"10003","key":"PORT-3","fields":{"summary":"Exportar a CSV","issuetype":{"name":"Sub-task","subtask":true,"hierarchyLevel":-1},
                    "status":{"name":"Won't Do","statusCategory":{"key":"done"}},"parent":{"id":"10002","key":"PORT-2"},
                    "created":"2026-09-03T08:00:00.000+0000","resolutiondate":"2026-09-10T08:00:00.000+0000",
                    "labels":[],"components":[],"attachment":[],
                    "issuelinks":[{"type":{"name":"Blocks","inward":"is blocked by","outward":"blocks"},"inwardIssue":{"id":"10002","key":"PORT-2"}}],
                    "comment":{"comments":[{"id":"c2","author":{"accountId":"u-luis","displayName":"Luis Muñoz"},"body":%s,"created":"2026-09-04T12:00:00.000+0000"}],"total":3}}}]}
                """.formatted(adf("uno")));
        bodies.put("comments-0", """
                {"startAt":0,"maxResults":100,"total":3,"comments":[
                  {"id":"c2","author":{"accountId":"u-luis","displayName":"Luis Muñoz"},"body":%s,"created":"2026-09-04T12:00:00.000+0000"},
                  {"id":"c3","author":{"accountId":"u-ana","displayName":"Ana Torres"},"body":%s,"created":"2026-09-05T12:00:00.000+0000"},
                  {"id":"c4","author":{"accountId":"u-luis","displayName":"Luis Muñoz"},"body":%s,"created":"2026-09-06T12:00:00.000+0000"}]}
                """.formatted(adf("uno"), adf("dos"), adf("tres")));
        List<String> progress = new ArrayList<>();

        ImportBundle bundle = source.fetch(connection, List.of("10000"), true, (done, total) -> progress.add(done + "/" + total));

        assertThat(progress).containsExactly("2/3", "3/3");
        ExtProject project = bundle.projects().get(0);
        assertThat(project.key()).isEqualTo("PORT");
        assertThat(project.name()).isEqualTo("Portal de clientes");
        assertThat(project.sprints()).extracting(sprint -> sprint.name() + " " + sprint.completedAt())
                .containsExactly("Sprint 1 2026-09-14T17:30:00Z", "Sprint 2 null");
        assertThat(project.statuses()).extracting(status -> status.name() + " " + status.category())
                .containsExactly("In Progress IN_PROGRESS", "In QA IN_PROGRESS", "Won't Do CANCELLED");

        ExtIssue epic = project.issues().get(0);
        assertThat(epic.kind()).isEqualTo(Kind.EPIC);
        assertThat(epic.number()).isEqualTo(1);

        ExtIssue story = project.issues().get(1);
        assertThat(story.kind()).isEqualTo(Kind.STORY);
        assertThat(story.type()).isEqualTo("Story");
        assertThat(story.description()).isEqualTo("Como cliente quiero ver mis pedidos");
        assertThat(story.parentId()).isEqualTo("10001");
        assertThat(story.priority()).isEqualTo("High");
        assertThat(story.assigneeIds()).containsExactly("u-luis");
        assertThat(story.creatorId()).isEqualTo("u-ana");
        assertThat(story.labels()).containsExactly("backend", "Pedidos");
        assertThat(story.sprintIds()).containsExactly("7", "8");
        assertThat(story.points()).isEqualTo(5.0);
        assertThat(story.secondsSpent()).isEqualTo(12000L);
        assertThat(story.dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(story.createdAt()).isEqualTo(Instant.parse("2026-09-02T15:00:00Z"));
        assertThat(story.url()).isEqualTo(base + "/browse/PORT-2");
        assertThat(story.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.name()).isEqualTo("mockup.png");
            assertThat(attachment.size()).isEqualTo(2048);
            assertThat(attachment.url()).isEqualTo(base + "/rest/api/3/attachment/content/501");
        });
        assertThat(story.links()).singleElement().satisfies(link -> {
            assertThat(link.type()).isEqualTo(LinkType.BLOCKS);
            assertThat(link.targetId()).isEqualTo("10003");
        });
        assertThat(story.comments()).singleElement().satisfies(comment -> {
            assertThat(comment.body()).isEqualTo("Revisa el paginado");
            assertThat(comment.authorId()).isEqualTo("u-ana");
        });

        ExtIssue subtask = project.issues().get(2);
        assertThat(subtask.kind()).isEqualTo(Kind.SUBTASK);
        assertThat(subtask.statusCategory()).isEqualTo(StatusCategory.CANCELLED);
        assertThat(subtask.completedAt()).isEqualTo(Instant.parse("2026-09-10T08:00:00Z"));
        assertThat(subtask.links()).singleElement().satisfies(link -> assertThat(link.type()).isEqualTo(LinkType.BLOCKED_BY));
        // La búsqueda traía 1 de 3 comentarios: los demás se pidieron aparte
        assertThat(subtask.commentCount()).isEqualTo(3);
        assertThat(subtask.comments()).extracting(comment -> comment.body()).containsExactly("uno", "dos", "tres");

        // El correo solo se conoce de quien lo deja ver
        assertThat(bundle.users()).extracting(user -> user.name() + " " + user.email())
                .containsExactlyInAnyOrder("Luis Muñoz luis@miempresa.com", "Ana Torres null");
        // La búsqueda pide los campos de sprint y de puntos que descubrió, y la segunda página con el token de la primera
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("POST /rest/api/3/search/jql")
                .contains("customfield_10020").contains("customfield_10016").contains("project = 10000"));
        assertThat(requests).anySatisfy(request -> assertThat(request).contains("\"nextPageToken\":\"pagina-2\""));
    }

    @Test
    void descargaUnAdjuntoSiguiendoLaRedireccion() {
        byte[] content = source.download(connection,
                new ImportBundle.ExtAttachment("501", "mockup.png", 21, "image/png", base + "/rest/api/3/attachment/content/501"));

        assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("contenido del archivo");
    }
}
