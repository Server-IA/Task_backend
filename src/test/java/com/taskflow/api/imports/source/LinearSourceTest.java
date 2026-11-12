package com.taskflow.api.imports.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.api.imports.ImportJson;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El conector de Linear contra un servidor falso que responde como su API GraphQL (las formas de las respuestas son
 * las de su esquema). Cada respuesta se elige por el nombre de la consulta y por sus variables.
 */
class LinearSourceTest {

    private static final String KEY = "lin_api_clave-de-prueba";
    private static final Pattern OPERATION = Pattern.compile("query (\\w+)");
    private static final String ANA = "{\"id\":\"u-ana\",\"name\":\"Ana Torres\",\"email\":\"ana@miempresa.com\"}";
    private static final String LUIS = "{\"id\":\"u-luis\",\"name\":\"Luis Muñoz\",\"email\":\"luis@miempresa.com\"}";
    private static final String LAST_PAGE = "{\"hasNextPage\":false,\"endCursor\":null}";

    private HttpServer server;
    private String base;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    /** La última consulta que se mandó con cada nombre. */
    private final Map<String, String> queries = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private LinearSource source;
    private SourceConnection connection;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
        source = new LinearSource(new SourceHttp(true)) {
            @Override
            String endpoint() {
                return base + "/graphql";
            }
        };
        connection = new SourceConnection(null, null, KEY);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        // La clave personal va sola: con «Bearer» delante Linear la toma por un token de OAuth y la rechaza
        if (!KEY.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "{\"errors\":[{\"message\":\"Authentication required\",\"extensions\":{\"code\":\"AUTHENTICATION_ERROR\"}}]}");
            return;
        }
        if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals("/graphql")
                || !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
            respond(exchange, 400, "{}");
            return;
        }
        JsonNode request = ImportJson.MAPPER.readTree(body);
        JsonNode variables = request.path("variables");
        Matcher matcher = OPERATION.matcher(request.path("query").asString(""));
        String operation = matcher.find() ? matcher.group(1) : "?";
        queries.put(operation, request.path("query").asString(""));
        synchronized (requests) {
            requests.add(operation + " " + variables);
        }
        String key = operation + (variables.has("issueId") ? " " + variables.path("issueId").asString("") : "")
                + (variables.has("after") ? " " + variables.path("after").asString("") : "");
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

    private static String comment(String id, String user, String body, String createdAt) {
        return "{\"id\":\"%s\",\"body\":\"%s\",\"createdAt\":\"%s\",\"user\":%s}".formatted(id, body, createdAt, user);
    }

    /** El equipo con cuatro issues en dos páginas; la segunda issue tiene más comentarios de los que trae la consulta. */
    private void team() {
        bodies.put("Team", "{\"data\":{\"team\":{\"id\":\"t-eng\",\"name\":\"Engineering\",\"key\":\"ENG\",\"issueCount\":4}}}");
        bodies.put("TeamIssues", """
                {"data":{"team":{"issues":{"nodes":[
                  {"id":"i-2","identifier":"ENG-2","number":2,"title":"Ver el historial de pedidos","description":"Pasos en **Markdown**",
                   "priority":2,"estimate":3,"dueDate":"2026-12-01","url":"https://linear.app/acme/issue/ENG-2/ver-el-historial-de-pedidos",
                   "createdAt":"2026-09-02T10:00:00.000Z","completedAt":null,"canceledAt":null,"archivedAt":null,
                   "state":{"id":"s-2","name":"In Progress","type":"started"},
                   "assignee":%2$s,"creator":null,"parent":{"id":"i-1"},"project":{"id":"p-q4","name":"Q4"},
                   "cycle":{"id":"cy-12","number":12,"name":null,"startsAt":"2026-09-29T00:00:00.000Z","endsAt":"2026-10-13T00:00:00.000Z","completedAt":null},
                   "labels":{"nodes":[{"id":"l-1","name":"Bug"},{"id":"l-2","name":"backend"}]},
                   "comments":{"nodes":[%3$s,%4$s],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-comentarios"}}},
                  {"id":"i-1","identifier":"ENG-1","number":1,"title":"Pedidos","description":null,
                   "priority":0,"estimate":null,"dueDate":null,"url":"https://linear.app/acme/issue/ENG-1/pedidos",
                   "createdAt":"2026-09-01T08:00:00.000Z","completedAt":null,"canceledAt":null,"archivedAt":null,
                   "state":{"id":"s-1","name":"Backlog","type":"backlog"},
                   "assignee":null,"creator":%1$s,"parent":null,"project":null,"cycle":null,
                   "labels":{"nodes":[]},"comments":{"nodes":[],"pageInfo":%5$s}}],
                 "pageInfo":{"hasNextPage":true,"endCursor":"cursor-2"}}}}}
                """.formatted(ANA, LUIS, comment("c-2", LUIS, "Ya quedó el filtro", "2026-09-04T12:00:00.000Z"),
                comment("c-1", ANA, "Revisa el paginado", "2026-09-03T12:00:00.000Z"), LAST_PAGE));
        bodies.put("TeamIssues cursor-2", """
                {"data":{"team":{"issues":{"nodes":[
                  {"id":"i-3","identifier":"ENG-3","number":3,"title":"Exportar a CSV","description":"",
                   "priority":1,"estimate":null,"dueDate":null,"url":"https://linear.app/acme/issue/ENG-3/exportar-a-csv",
                   "createdAt":"2026-09-03T08:00:00.000Z","completedAt":null,"canceledAt":"2026-09-10T08:00:00.000Z",
                   "archivedAt":"2026-10-01T00:00:00.000Z","state":{"id":"s-4","name":"Canceled","type":"canceled"},
                   "assignee":null,"creator":%1$s,"parent":{"id":"i-2"},"project":{"id":"p-q4","name":"Q4"},
                   "cycle":{"id":"cy-11","number":11,"name":"Lanzamiento","startsAt":"2026-09-15T00:00:00.000Z","endsAt":"2026-09-29T00:00:00.000Z","completedAt":"2026-09-29T00:00:00.000Z"},
                   "labels":{"nodes":[]},
                   "comments":{"nodes":[%2$s],"pageInfo":%3$s}},
                  {"id":"i-4","identifier":"ENG-4","number":4,"title":"Arreglar el inicio de sesión","description":null,
                   "priority":4,"estimate":1.5,"dueDate":null,"url":"https://linear.app/acme/issue/ENG-4/arreglar-el-inicio-de-sesion",
                   "createdAt":"2026-09-04T08:00:00.000Z","completedAt":"2026-09-20T12:00:00.000Z","canceledAt":null,"archivedAt":null,
                   "state":{"id":"s-3","name":"Done","type":"completed"},
                   "assignee":%1$s,"creator":%1$s,"parent":null,"project":null,
                   "cycle":{"id":"cy-11","number":11,"name":"Lanzamiento","startsAt":"2026-09-15T00:00:00.000Z","endsAt":"2026-09-29T00:00:00.000Z","completedAt":"2026-09-29T00:00:00.000Z"},
                   "labels":{"nodes":[]},"comments":{"nodes":[],"pageInfo":%3$s}}],
                 "pageInfo":{"hasNextPage":false,"endCursor":"cursor-3"}}}}}
                """.formatted(ANA, comment("c-9", "null", "Cerrada desde GitHub", "2026-09-10T08:00:00.000Z"), LAST_PAGE));
        bodies.put("IssueComments i-2 cursor-comentarios", """
                {"data":{"issue":{"comments":{"nodes":[%s],"pageInfo":%s}}}}
                """.formatted(comment("c-3", "{\"id\":\"u-marta\",\"name\":\"Marta Ríos\",\"email\":\"marta@miempresa.com\"}", "Falta probarlo",
                "2026-09-05T12:00:00.000Z"), LAST_PAGE));
    }

    @Test
    void conectaYListaLosEquiposPorPaginas() {
        bodies.put("Me", "{\"data\":{\"viewer\":" + ANA + "}}");
        bodies.put("Teams", """
                {"data":{"teams":{"nodes":[{"id":"t-eng","name":"Engineering","key":"ENG"}],
                 "pageInfo":{"hasNextPage":true,"endCursor":"cursor-equipos"}}}}""");
        bodies.put("Teams cursor-equipos", """
                {"data":{"teams":{"nodes":[{"id":"t-dis","name":"Diseño","key":"DIS"}],
                 "pageInfo":{"hasNextPage":false,"endCursor":"cursor-fin"}}}}""");

        ImportSource.Discovery discovery = source.discover(connection);

        assertThat(discovery.account()).isEqualTo("Ana Torres");
        assertThat(discovery.label()).isEqualTo("linear.app");
        assertThat(discovery.projects()).containsExactly(
                new ImportSource.Found("t-eng", "ENG", "Engineering", -1),
                new ImportSource.Found("t-dis", "DIS", "Diseño", -1));
        assertThat(requests).containsExactly("Me {}", "Teams {}", "Teams {\"after\":\"cursor-equipos\"}");
    }

    @Test
    void unaClaveMalaSeLeDiceALaPersona() {
        SourceConnection wrong = new SourceConnection(null, null, "lin_api_otra");

        assertThatThrownBy(() -> source.discover(wrong)).isInstanceOf(SourceException.class).hasMessage(SourceHttp.REJECTED);
    }

    @Test
    void laClaveNoLlevaBearerDelante() {
        // El servidor falso solo acepta la clave sola, como Linear: si el conector le pusiera «Bearer», esto fallaría
        bodies.put("Me", "{\"data\":{\"viewer\":" + ANA + "}}");
        bodies.put("Teams", "{\"data\":{\"teams\":{\"nodes\":[],\"pageInfo\":" + LAST_PAGE + "}}}");

        assertThat(source.discover(connection).projects()).isEmpty();
        // Y quien pega la clave con el prefijo no llega a mandarla
        assertThatThrownBy(() -> source.discover(new SourceConnection(null, null, "Bearer " + KEY)))
                .isInstanceOf(SourceException.class).hasMessage(SourceHttp.REJECTED);
        assertThat(requests).containsExactly("Me {}", "Teams {}");
    }

    @Test
    void unErrorDeGraphQLLlegaConUn200YSeLeExplicaALaPersona() {
        Map<String, String> messages = Map.of(
                "AUTHENTICATION_ERROR", SourceHttp.REJECTED,
                "FORBIDDEN", SourceHttp.FORBIDDEN,
                "RATELIMITED", SourceHttp.BUSY,
                "INTERNAL_SERVER_ERROR", SourceHttp.UNREACHABLE);
        messages.forEach((code, message) -> {
            bodies.put("Me", "{\"data\":null,\"errors\":[{\"message\":\"Something went wrong\",\"extensions\":{\"code\":\"" + code + "\"}}]}");

            assertThatThrownBy(() -> source.discover(connection)).as(code).isInstanceOf(SourceException.class).hasMessage(message);
        });
    }

    @Test
    void traeLasIssuesPorPaginasConSuJerarquiaEstadosCiclosProyectosYComentarios() {
        team();
        List<String> progress = new ArrayList<>();

        ImportBundle bundle = source.fetch(connection, List.of("t-eng"), true, (done, total) -> progress.add(done + "/" + total));

        assertThat(progress).containsExactly("2/4", "4/4");
        ExtProject project = bundle.projects().get(0);
        assertThat(project.id()).isEqualTo("t-eng");
        assertThat(project.key()).isEqualTo("ENG");
        assertThat(project.name()).isEqualTo("Engineering");
        // Del backlog a lo cancelado, como en Linear
        assertThat(project.statuses()).extracting(status -> status.name() + " " + status.category())
                .containsExactly("Backlog BACKLOG", "In Progress IN_PROGRESS", "Done DONE", "Canceled CANCELLED");
        // Los ciclos son sprints: uno sin nombre se llama por su número
        assertThat(project.sprints()).extracting(sprint -> sprint.id() + " " + sprint.name() + " " + sprint.startDate() + " " + sprint.endDate()
                + " " + sprint.completedAt()).containsExactly("cy-11 Lanzamiento 2026-09-15 2026-09-29 2026-09-29T00:00:00Z",
                "cy-12 Cycle 12 2026-09-29 2026-10-13 null");
        // Los proyectos de Linear son módulos
        assertThat(project.modules()).extracting(module -> module.id() + " " + module.name()).containsExactly("p-q4 Q4");
        // De la más vieja a la más nueva, aunque lleguen en otro orden
        assertThat(project.issues()).extracting(ExtIssue::key).containsExactly("ENG-1", "ENG-2", "ENG-3", "ENG-4");

        ExtIssue parent = project.issues().get(0);
        assertThat(parent.statusCategory()).isEqualTo(StatusCategory.BACKLOG);
        assertThat(parent.parentId()).isNull();
        // Prioridad 0 es «sin prioridad»
        assertThat(parent.priority()).isNull();
        assertThat(parent.description()).isEmpty();
        assertThat(parent.assigneeIds()).isEmpty();
        assertThat(parent.creatorId()).isEqualTo("u-ana");
        assertThat(parent.points()).isNull();
        assertThat(parent.moduleIds()).isEmpty();
        assertThat(parent.sprintIds()).isEmpty();

        ExtIssue issue = project.issues().get(1);
        assertThat(issue.id()).isEqualTo("i-2");
        assertThat(issue.number()).isEqualTo(2);
        // Linear no tiene tipos: ni la etiqueta «Bug» la vuelve un error
        assertThat(issue.type()).isNull();
        assertThat(issue.kind()).isEqualTo(Kind.TASK);
        assertThat(issue.labels()).containsExactly("Bug", "backend");
        assertThat(issue.title()).isEqualTo("Ver el historial de pedidos");
        assertThat(issue.description()).isEqualTo("Pasos en **Markdown**");
        assertThat(issue.status()).isEqualTo("In Progress");
        assertThat(issue.statusCategory()).isEqualTo(StatusCategory.IN_PROGRESS);
        assertThat(issue.priority()).isEqualTo("High");
        assertThat(issue.assigneeIds()).containsExactly("u-luis");
        // La creó una integración
        assertThat(issue.creatorId()).isNull();
        assertThat(issue.parentId()).isEqualTo("i-1");
        assertThat(issue.moduleIds()).containsExactly("p-q4");
        assertThat(issue.sprintIds()).containsExactly("cy-12");
        assertThat(issue.points()).isEqualTo(3.0);
        assertThat(issue.dueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(issue.createdAt()).isEqualTo(Instant.parse("2026-09-02T10:00:00Z"));
        assertThat(issue.completedAt()).isNull();
        assertThat(issue.url()).isEqualTo("https://linear.app/acme/issue/ENG-2/ver-el-historial-de-pedidos");
        assertThat(issue.archived()).isFalse();
        // Lo que Linear llama adjuntos son enlaces
        assertThat(issue.attachments()).isEmpty();
        // La consulta traía 2 comentarios y avisaba de que había más: el tercero se pidió aparte. Del más viejo al más nuevo
        assertThat(issue.commentCount()).isEqualTo(3);
        assertThat(issue.comments()).extracting(comment -> comment.id() + " " + comment.authorId() + " " + comment.body())
                .containsExactly("c-1 u-ana Revisa el paginado", "c-2 u-luis Ya quedó el filtro", "c-3 u-marta Falta probarlo");

        ExtIssue cancelled = project.issues().get(2);
        assertThat(cancelled.statusCategory()).isEqualTo(StatusCategory.CANCELLED);
        assertThat(cancelled.priority()).isEqualTo("Urgent");
        assertThat(cancelled.parentId()).isEqualTo("i-2");
        // Sin fecha de terminada, vale la de cancelada
        assertThat(cancelled.completedAt()).isEqualTo(Instant.parse("2026-09-10T08:00:00Z"));
        assertThat(cancelled.archived()).isTrue();
        assertThat(cancelled.sprintIds()).containsExactly("cy-11");
        // El comentario de una integración no tiene autor
        assertThat(cancelled.comments()).singleElement().satisfies(comment -> {
            assertThat(comment.authorId()).isNull();
            assertThat(comment.authorName()).isNull();
            assertThat(comment.body()).isEqualTo("Cerrada desde GitHub");
        });

        ExtIssue done = project.issues().get(3);
        assertThat(done.statusCategory()).isEqualTo(StatusCategory.DONE);
        assertThat(done.priority()).isEqualTo("Low");
        assertThat(done.points()).isEqualTo(1.5);
        assertThat(done.completedAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));

        // Cada persona una sola vez, con su correo
        assertThat(bundle.users()).extracting(user -> user.id() + " " + user.name() + " " + user.email()).containsExactlyInAnyOrder(
                "u-ana Ana Torres ana@miempresa.com", "u-luis Luis Muñoz luis@miempresa.com", "u-marta Marta Ríos marta@miempresa.com");
        // La segunda página se pide con el cursor de la primera, y los comentarios que faltan, desde donde se quedó la consulta
        assertThat(requests).containsExactly("Team {\"teamId\":\"t-eng\"}", "TeamIssues {\"teamId\":\"t-eng\"}",
                "IssueComments {\"issueId\":\"i-2\",\"after\":\"cursor-comentarios\"}", "TeamIssues {\"teamId\":\"t-eng\",\"after\":\"cursor-2\"}");
        // Todas las listas anidadas llevan su tamaño (si no, la consulta pasa del tope de complejidad), y se piden las archivadas
        assertThat(queries.get("TeamIssues")).contains("issues(first: 50, after: $after, includeArchived: true")
                .contains("labels(first: 20)").contains("comments(first: 20) { nodes { id body createdAt user { id name email } }");
        assertThat(queries.get("IssueComments")).contains("comments(first: 50, after: $after)");
    }

    @Test
    void sinComentariosSoloLosCuentaYNoPideLosQueFaltan() {
        team();

        ImportBundle bundle = source.fetch(connection, List.of("t-eng"), false, (done, total) -> {
        });

        ExtIssue issue = bundle.projects().get(0).issues().get(1);
        assertThat(issue.comments()).isEmpty();
        assertThat(issue.commentCount()).isEqualTo(2);
        assertThat(requests).noneMatch(request -> request.startsWith("IssueComments"));
        assertThat(queries.get("TeamIssues")).contains("comments(first: 20) { nodes { id } pageInfo");
        // Quien solo aparece en los comentarios no entra
        assertThat(bundle.users()).extracting(user -> user.id()).containsExactlyInAnyOrder("u-ana", "u-luis");
    }

    @Test
    void unEquipoQueNoExisteSeLeDiceALaPersona() {
        bodies.put("Team", "{\"data\":{\"team\":null}}");

        assertThatThrownBy(() -> source.fetch(connection, List.of("t-no"), true, (done, total) -> {
        })).isInstanceOf(SourceException.class).hasMessage(SourceHttp.NOT_FOUND);
    }

    @Test
    void noHayArchivosQueDescargar() {
        assertThatThrownBy(() -> source.download(connection, new ImportBundle.ExtAttachment("a-1", "PR #42", 0, null,
                "https://github.com/acme/app/pull/42"))).isInstanceOf(SourceException.class).hasMessage(SourceHttp.UNREACHABLE);
        assertThat(requests).isEmpty();
    }
}
