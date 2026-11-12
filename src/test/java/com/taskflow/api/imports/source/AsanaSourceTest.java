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
 * El conector de Asana contra un servidor falso que responde como su API REST (las formas de las respuestas son las de
 * su documentación: todo envuelto en {@code data}, y las listas con {@code next_page}).
 */
class AsanaSourceTest {

    private static final String BEARER = "Bearer token-de-prueba";
    private static final String EMPTY = "{\"data\":[],\"next_page\":null}";

    private HttpServer server;
    private String base;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    /** Lo que Asana no deja ver a esta cuenta. */
    private final List<String> forbidden = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private AsanaSource source;
    private SourceConnection connection;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
        source = new AsanaSource(new SourceHttp(true)) {
            @Override
            String api() {
                return base + "/api/1.0";
            }
        };
        connection = new SourceConnection(null, null, "token-de-prueba");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String raw = exchange.getRequestURI().getQuery();
        Map<String, String> query = new HashMap<>();
        for (String pair : raw == null ? new String[0] : raw.split("&")) {
            query.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        synchronized (requests) {
            requests.add(exchange.getRequestMethod() + " " + path + (raw == null ? "" : "?" + raw) + " [" + (authorization == null ? "" : authorization) + "]");
        }
        if (path.startsWith("/descarga/")) {
            // La dirección firmada es de otro servidor: vale por su firma, y el token de Asana no debe llegarle
            boolean allowed = authorization == null && "nueva".equals(query.get("firma"));
            respond(exchange, allowed ? 200 : 403, allowed ? "contenido del archivo" : "{}");
            return;
        }
        if (!BEARER.equals(authorization)) {
            respond(exchange, 401, "{\"errors\":[{\"message\":\"Not Authorized\"}]}");
            return;
        }
        if (forbidden.contains(path)) {
            respond(exchange, 403, "{\"errors\":[{\"message\":\"Forbidden\"}]}");
            return;
        }
        String key = path.substring("/api/1.0".length()) + (query.containsKey("parent") ? " parent=" + query.get("parent") : "")
                + (query.containsKey("offset") ? " offset=" + query.get("offset") : "");
        String response = bodies.get(key);
        if (response == null && (path.endsWith("/sections") || path.endsWith("/stories") || path.endsWith("/subtasks") || path.endsWith("/attachments"))) {
            response = EMPTY;
        }
        respond(exchange, response == null ? 404 : 200, response == null ? "{\"errors\":[{\"message\":\"Not Found\"}]}" : response);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String nextPage(String offset) {
        return "{\"offset\":\"%1$s\",\"path\":\"/x?limit=100&offset=%1$s\",\"uri\":\"https://app.asana.com/api/1.0/x?limit=100&offset=%1$s\"}".formatted(offset);
    }

    /** Un proyecto con tres tareas en dos páginas; la primera tiene una subtarea y esa, otra. */
    private void project() {
        bodies.put("/projects/p-1", """
                {"data":{"gid":"p-1","resource_type":"project","name":"Portal de clientes","notes":"El portal nuevo",
                 "permalink_url":"https://app.asana.com/1/12345/project/p-1"}}""");
        bodies.put("/projects/p-1/task_counts", "{\"data\":{\"num_tasks\":3}}");
        bodies.put("/projects/p-1/sections", """
                {"data":[{"gid":"s-1","resource_type":"section","name":"Por hacer"},{"gid":"s-2","resource_type":"section","name":"En curso"},
                         {"gid":"s-3","resource_type":"section","name":"Hecho"}],"next_page":null}""");
        bodies.put("/projects/p-1/tasks", """
                {"data":[
                  {"gid":"t-1","resource_type":"task","resource_subtype":"default_task","name":"Ver el historial de pedidos",
                   "notes":"Como cliente quiero ver mis pedidos","completed":false,"completed_at":null,
                   "created_at":"2026-09-02T10:00:00.000Z","due_on":"2026-10-15","start_on":"2026-10-01",
                   "assignee":{"gid":"u-luis","resource_type":"user","name":"Luis Muñoz"},
                   "memberships":[{"project":{"gid":"p-otro","resource_type":"project","name":"Hoja de ruta"},
                                   "section":{"gid":"s-9","resource_type":"section","name":"Este trimestre"}},
                                  {"project":{"gid":"p-1","resource_type":"project","name":"Portal de clientes"},
                                   "section":{"gid":"s-2","resource_type":"section","name":"En curso"}}],
                   "tags":[{"gid":"g-1","name":"backend"},{"gid":"g-2","name":"Grade A"}],"parent":null,"num_subtasks":1,
                   "permalink_url":"https://app.asana.com/1/12345/task/t-1","created_by":{"gid":"u-ana","resource_type":"user"}},
                  {"gid":"t-2","resource_type":"task","resource_subtype":"milestone","name":"Lanzamiento","notes":"","completed":true,
                   "completed_at":"2026-09-20T12:00:00.000Z","created_at":"2026-09-01T08:00:00.000Z","due_on":"2026-09-20","start_on":null,
                   "assignee":null,
                   "memberships":[{"project":{"gid":"p-1","resource_type":"project","name":"Portal de clientes"},
                                   "section":{"gid":"s-2","resource_type":"section","name":"En curso"}}],
                   "tags":[],"parent":null,"num_subtasks":0,"permalink_url":"https://app.asana.com/1/12345/task/t-2","created_by":null}],
                 "next_page":%s}""".formatted(nextPage("tareas-2")));
        bodies.put("/projects/p-1/tasks offset=tareas-2", """
                {"data":[
                  {"gid":"t-3","resource_type":"task","resource_subtype":"default_task","name":"Idea suelta","notes":"","completed":false,
                   "completed_at":null,"created_at":"2026-09-03T08:00:00.000Z","due_on":null,"start_on":null,
                   "assignee":{"gid":"u-externo","resource_type":"user","name":"Persona Externa"},
                   "memberships":[{"project":{"gid":"p-1","resource_type":"project","name":"Portal de clientes"},"section":null}],
                   "tags":[],"parent":null,"num_subtasks":0,"permalink_url":"https://app.asana.com/1/12345/task/t-3",
                   "created_by":{"gid":"u-ana","resource_type":"user"}}],
                 "next_page":null}""");
        bodies.put("/tasks/t-1/subtasks", """
                {"data":[
                  {"gid":"t-1-a","resource_type":"task","resource_subtype":"default_task","name":"Armar la consulta","notes":"","completed":false,
                   "completed_at":null,"created_at":"2026-09-02T11:00:00.000Z","due_on":null,"start_on":null,"assignee":null,"memberships":[],
                   "tags":[],"parent":{"gid":"t-1","resource_type":"task","name":"Ver el historial de pedidos"},"num_subtasks":1,
                   "permalink_url":"https://app.asana.com/1/12345/task/t-1-a","created_by":{"gid":"u-luis","resource_type":"user"}}],
                 "next_page":null}""");
        // Esta no dice quién es su padre: es la tarea bajo la que se encontró
        bodies.put("/tasks/t-1-a/subtasks", """
                {"data":[
                  {"gid":"t-1-b","resource_type":"task","resource_subtype":"default_task","name":"Probar el filtro","completed":true,
                   "completed_at":"2026-09-05T09:00:00.000Z","created_at":"2026-09-02T12:00:00.000Z","num_subtasks":0}],
                 "next_page":null}""");
        bodies.put("/tasks/t-1/stories", """
                {"data":[
                  {"gid":"h-1","resource_type":"story","type":"comment","resource_subtype":"comment_added","text":"Revisa el paginado",
                   "created_at":"2026-09-03T12:00:00.000Z","created_by":{"gid":"u-ana","resource_type":"user","name":"Ana Torres"}}],
                 "next_page":%s}""".formatted(nextPage("historias-2")));
        bodies.put("/tasks/t-1/stories offset=historias-2", """
                {"data":[
                  {"gid":"h-2","resource_type":"story","type":"system","resource_subtype":"assigned","text":"Ana Torres assigned to Luis Muñoz",
                   "created_at":"2026-09-03T13:00:00.000Z","created_by":{"gid":"u-ana","resource_type":"user","name":"Ana Torres"}},
                  {"gid":"h-3","resource_type":"story","type":"comment","resource_subtype":"comment_added","text":"Ya quedó el filtro",
                   "created_at":"2026-09-04T12:00:00.000Z","created_by":{"gid":"u-luis","resource_type":"user","name":"Luis Muñoz"}}],
                 "next_page":null}""");
        bodies.put("/attachments parent=t-1", """
                {"data":[
                  {"gid":"a-1","resource_type":"attachment","name":"mockup.png","resource_subtype":"asana","host":"asana","size":2048,
                   "download_url":"https://s3.amazonaws.com/assets/1234/mockup.png?firma=vieja"},
                  {"gid":"a-2","resource_type":"attachment","name":"Diseño en Drive","resource_subtype":"gdrive","host":"gdrive",
                   "download_url":"https://drive.google.com/file/d/abc"}],
                 "next_page":null}""");
        bodies.put("/users/u-ana", "{\"data\":{\"gid\":\"u-ana\",\"resource_type\":\"user\",\"name\":\"Ana Torres\",\"email\":\"ana@miempresa.com\"}}");
        bodies.put("/users/u-luis", "{\"data\":{\"gid\":\"u-luis\",\"resource_type\":\"user\",\"name\":\"Luis Muñoz\",\"email\":\"luis@miempresa.com\"}}");
        forbidden.add("/api/1.0/users/u-externo");
    }

    @Test
    void conectaYListaLosProyectosDeCadaWorkspacePorPaginas() {
        bodies.put("/users/me", """
                {"data":{"gid":"u-ana","resource_type":"user","name":"Ana Torres","email":"ana@miempresa.com",
                 "workspaces":[{"gid":"w-1","resource_type":"workspace","name":"Mi Empresa"},
                               {"gid":"w-2","resource_type":"workspace","name":"Personal"}]}}""");
        bodies.put("/workspaces/w-1/projects", """
                {"data":[{"gid":"p-1","resource_type":"project","name":"Portal de clientes"}],"next_page":%s}""".formatted(nextPage("proyectos-2")));
        bodies.put("/workspaces/w-1/projects offset=proyectos-2", """
                {"data":[{"gid":"p-2","resource_type":"project","name":"Soporte interno"}],"next_page":null}""");
        bodies.put("/workspaces/w-2/projects", """
                {"data":[{"gid":"p-3","resource_type":"project","name":"Mudanza"}],"next_page":null}""");

        ImportSource.Discovery discovery = source.discover(connection);

        assertThat(discovery.account()).isEqualTo("Ana Torres");
        assertThat(discovery.label()).isEqualTo("app.asana.com");
        // Asana no dice cuántas tareas tiene un proyecto sin otra petición por proyecto
        assertThat(discovery.projects()).containsExactly(
                new ImportSource.Found("p-1", null, "Portal de clientes", -1),
                new ImportSource.Found("p-2", null, "Soporte interno", -1),
                new ImportSource.Found("p-3", null, "Mudanza", -1));
        assertThat(requests).containsExactly(
                "GET /api/1.0/users/me?opt_fields=name,email,workspaces.name [" + BEARER + "]",
                "GET /api/1.0/workspaces/w-1/projects?archived=false&opt_fields=name&limit=100 [" + BEARER + "]",
                "GET /api/1.0/workspaces/w-1/projects?archived=false&opt_fields=name&limit=100&offset=proyectos-2 [" + BEARER + "]",
                "GET /api/1.0/workspaces/w-2/projects?archived=false&opt_fields=name&limit=100 [" + BEARER + "]");
    }

    @Test
    void unTokenMaloSeLeDiceALaPersona() {
        SourceConnection wrong = new SourceConnection(null, null, "otro");

        assertThatThrownBy(() -> source.discover(wrong)).isInstanceOf(SourceException.class).hasMessage(SourceHttp.REJECTED);
    }

    @Test
    void traeLasTareasPorPaginasConSusSubtareasSeccionesComentariosYAdjuntos() {
        project();
        List<String> progress = new ArrayList<>();

        ImportBundle bundle = source.fetch(connection, List.of("p-1"), true, (done, total) -> progress.add(done + "/" + total));

        // El proyecto dice tener 3 tareas; las subtareas se suman conforme aparecen
        assertThat(progress).containsExactly("1/3", "2/4", "3/5", "4/5", "5/5");
        ExtProject project = bundle.projects().get(0);
        assertThat(project.id()).isEqualTo("p-1");
        assertThat(project.key()).isNull();
        assertThat(project.name()).isEqualTo("Portal de clientes");
        assertThat(project.description()).isEqualTo("El portal nuevo");
        assertThat(project.url()).isEqualTo("https://app.asana.com/1/12345/project/p-1");
        // Las secciones en su orden; arriba lo que no tiene sección y al final lo completado, que en Asana no es una sección
        assertThat(project.statuses()).extracting(status -> status.name() + " " + status.category())
                .containsExactly("Untitled section TODO", "Por hacer TODO", "En curso IN_PROGRESS", "Hecho DONE", "Completed DONE");
        // Cada tarea seguida de sus subtareas
        assertThat(project.issues()).extracting(ExtIssue::id).containsExactly("t-1", "t-1-a", "t-1-b", "t-2", "t-3");

        ExtIssue task = project.issues().get(0);
        assertThat(task.key()).isNull();
        assertThat(task.number()).isNull();
        assertThat(task.type()).isNull();
        assertThat(task.kind()).isEqualTo(Kind.TASK);
        assertThat(task.title()).isEqualTo("Ver el historial de pedidos");
        assertThat(task.description()).isEqualTo("Como cliente quiero ver mis pedidos");
        // Su sección en este proyecto, no la que tiene en el otro donde también está
        assertThat(task.status()).isEqualTo("En curso");
        assertThat(task.statusCategory()).isEqualTo(StatusCategory.IN_PROGRESS);
        assertThat(task.priority()).isNull();
        assertThat(task.assigneeIds()).containsExactly("u-luis");
        assertThat(task.creatorId()).isEqualTo("u-ana");
        assertThat(task.labels()).containsExactly("backend", "Grade A");
        assertThat(task.parentId()).isNull();
        assertThat(task.startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(task.dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(task.createdAt()).isEqualTo(Instant.parse("2026-09-02T10:00:00Z"));
        assertThat(task.completedAt()).isNull();
        assertThat(task.url()).isEqualTo("https://app.asana.com/1/12345/task/t-1");
        // Los comentarios de las dos páginas; «la asignó» es historia de la tarea, no un comentario
        assertThat(task.commentCount()).isEqualTo(2);
        assertThat(task.comments()).extracting(comment -> comment.id() + " " + comment.authorId() + " " + comment.authorName() + ": " + comment.body())
                .containsExactly("h-1 u-ana Ana Torres: Revisa el paginado", "h-3 u-luis Luis Muñoz: Ya quedó el filtro");
        // Solo el archivo que guarda Asana; el de Drive es un enlace
        assertThat(task.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.id()).isEqualTo("a-1");
            assertThat(attachment.name()).isEqualTo("mockup.png");
            assertThat(attachment.size()).isEqualTo(2048);
            assertThat(attachment.url()).isEqualTo("https://s3.amazonaws.com/assets/1234/mockup.png?firma=vieja");
        });

        ExtIssue subtask = project.issues().get(1);
        assertThat(subtask.kind()).isEqualTo(Kind.SUBTASK);
        assertThat(subtask.parentId()).isEqualTo("t-1");
        // Las subtareas no están en ninguna sección
        assertThat(subtask.status()).isEqualTo("Untitled section");
        assertThat(subtask.statusCategory()).isEqualTo(StatusCategory.TODO);
        assertThat(subtask.creatorId()).isEqualTo("u-luis");

        ExtIssue nested = project.issues().get(2);
        assertThat(nested.kind()).isEqualTo(Kind.SUBTASK);
        assertThat(nested.parentId()).isEqualTo("t-1-a");
        assertThat(nested.status()).isEqualTo("Completed");
        assertThat(nested.statusCategory()).isEqualTo(StatusCategory.DONE);
        assertThat(nested.description()).isEmpty();
        assertThat(nested.labels()).isEmpty();

        // Completar no la sacó de «En curso»: entra como completada, no con el estado de su sección
        ExtIssue milestone = project.issues().get(3);
        assertThat(milestone.kind()).isEqualTo(Kind.TASK);
        assertThat(milestone.type()).isNull();
        assertThat(milestone.status()).isEqualTo("Completed");
        assertThat(milestone.statusCategory()).isEqualTo(StatusCategory.DONE);
        assertThat(milestone.completedAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));
        assertThat(milestone.assigneeIds()).isEmpty();
        assertThat(milestone.creatorId()).isNull();

        ExtIssue loose = project.issues().get(4);
        assertThat(loose.status()).isEqualTo("Untitled section");
        assertThat(loose.statusCategory()).isEqualTo(StatusCategory.TODO);
        assertThat(loose.assigneeIds()).containsExactly("u-externo");

        // Cada persona una vez, con su correo; a quien esta cuenta no puede ver, con el nombre que traía la tarea
        assertThat(bundle.users()).extracting(user -> user.id() + " " + user.name() + " " + user.email())
                .containsExactly("u-luis Luis Muñoz luis@miempresa.com", "u-ana Ana Torres ana@miempresa.com", "u-externo Persona Externa null");
        assertThat(requests).filteredOn(request -> request.contains("/users/")).hasSize(3);
        // Todas las listas se piden con `limit`, y la página siguiente con el `offset` que dio la anterior
        assertThat(requests).filteredOn(request -> request.matches("GET /api/1\\.0/(projects/p-1/(sections|tasks)|tasks/[^/]+/(subtasks|stories)|attachments)\\?.*"))
                .hasSize(1 + 2 + 2 + 6 + 5).allSatisfy(request -> assertThat(request).contains("&limit=100"));
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("GET /api/1.0/projects/p-1/tasks?").contains("&offset=tareas-2"));
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("GET /api/1.0/tasks/t-1/stories?")
                .contains("resource_subtype=comment_added").contains("&offset=historias-2"));
        assertThat(requests).anySatisfy(request -> assertThat(request).startsWith("GET /api/1.0/attachments?parent=t-1&"));
        assertThat(requests).allSatisfy(request -> assertThat(request).endsWith("[" + BEARER + "]"));
    }

    @Test
    void sinComentariosNoPideLasHistorias() {
        project();

        ImportBundle bundle = source.fetch(connection, List.of("p-1"), false, (done, total) -> {
        });

        ExtIssue task = bundle.projects().get(0).issues().get(0);
        assertThat(task.comments()).isEmpty();
        assertThat(task.commentCount()).isZero();
        assertThat(requests).noneMatch(request -> request.contains("/stories"));
    }

    @Test
    void unaSubtareaQueTambienEstaEnElProyectoEntraUnaSolaVez() {
        bodies.put("/projects/p-2", "{\"data\":{\"gid\":\"p-2\",\"name\":\"Soporte interno\"}}");
        bodies.put("/projects/p-2/tasks", """
                {"data":[
                  {"gid":"t-9","name":"Responder el correo","completed":false,"parent":{"gid":"t-8","name":"Atender el caso"},"num_subtasks":0,
                   "memberships":[{"project":{"gid":"p-2"},"section":{"gid":"s-1","name":"Pendientes"}}]},
                  {"gid":"t-8","name":"Atender el caso","completed":false,"parent":null,"num_subtasks":1,
                   "memberships":[{"project":{"gid":"p-2"},"section":{"gid":"s-1","name":"Pendientes"}}]}],
                 "next_page":null}""");
        bodies.put("/tasks/t-8/subtasks", """
                {"data":[{"gid":"t-9","name":"Responder el correo","completed":false,"parent":{"gid":"t-8"},"num_subtasks":0}],"next_page":null}""");
        List<String> progress = new ArrayList<>();

        // El proyecto no contesta cuántas tareas tiene: se sigue sin saberlo
        ImportBundle bundle = source.fetch(connection, List.of("p-2"), false, (done, total) -> progress.add(done + "/" + total));

        ExtProject project = bundle.projects().get(0);
        assertThat(project.description()).isNull();
        assertThat(project.url()).isNull();
        assertThat(project.issues()).extracting(issue -> issue.id() + " " + issue.kind() + " " + issue.parentId() + " " + issue.status())
                .containsExactly("t-9 SUBTASK t-8 Pendientes", "t-8 TASK null Pendientes");
        // Un estado que no venía entre las secciones también queda en la lista
        assertThat(project.statuses()).extracting(status -> status.name() + " " + status.category()).containsExactly("Pendientes BACKLOG");
        assertThat(progress).containsExactly("1/1", "2/2");
    }

    @Test
    void descargaUnAdjuntoPidiendoAntesUnaDireccionFrescaYSinMandarleElToken() {
        bodies.put("/attachments/a-1", """
                {"data":{"gid":"a-1","resource_type":"attachment","name":"mockup.png","download_url":"%s/descarga/a-1?firma=nueva"}}""".formatted(base));

        // La dirección que se guardó al leer el proyecto ya caducó
        byte[] content = source.download(connection, new ImportBundle.ExtAttachment("a-1", "mockup.png", 2048, null,
                base + "/descarga/a-1?firma=vieja"));

        assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("contenido del archivo");
        assertThat(requests).containsExactly("GET /api/1.0/attachments/a-1?opt_fields=name,download_url [" + BEARER + "]",
                "GET /descarga/a-1?firma=nueva []");
    }
}
