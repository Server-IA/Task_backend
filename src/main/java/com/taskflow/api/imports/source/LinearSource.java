package com.taskflow.api.imports.source;

import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.imports.csv.CsvImport;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;
import com.taskflow.api.imports.model.ImportBundle.ExtComment;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtModule;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtSprint;
import com.taskflow.api.imports.model.ImportBundle.ExtStatus;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.taskflow.api.imports.source.SourceValues.blankToNull;
import static com.taskflow.api.imports.source.SourceValues.credential;
import static com.taskflow.api.imports.source.SourceValues.date;
import static com.taskflow.api.imports.source.SourceValues.instant;
import static com.taskflow.api.imports.source.SourceValues.text;

/**
 * Linear por su API GraphQL, con una clave de API personal. Un equipo de Linear es un proyecto de TaskFlow; sus
 * proyectos (que agrupan issues sin ser una) llegan como módulos y sus ciclos, como sprints. Linear no tiene tipos de
 * issue: todas son tareas, también las que llevan una etiqueta «Bug».
 * <p>
 * Linear rechaza una consulta de más de 10 000 puntos, y cada lista anidada multiplica lo que cuesta lo de adentro por
 * su {@code first} (50 si no se dice). Por eso todas llevan un {@code first} fijo: con 50 issues por página, 20
 * etiquetas y 20 comentarios cada una, la consulta ronda los 4 500 puntos. A la issue que tenga más comentarios de los
 * que caben se le piden los demás aparte.
 * <p>
 * Lo que Linear llama «attachments» son enlaces a otras herramientas (un pull request, un ticket), no archivos: no se
 * importan como adjuntos.
 */
@Component
@RequiredArgsConstructor
public class LinearSource implements ImportSource {

    private static final String ENDPOINT = "https://api.linear.app/graphql";
    private static final String LABEL = "linear.app";

    private static final String VIEWER = "query Me { viewer { id name email } }";
    private static final String TEAMS = """
            query Teams($after: String) {
              teams(first: 50, after: $after) { nodes { id name key } pageInfo { hasNextPage endCursor } }
            }""";
    private static final String TEAM = """
            query Team($teamId: String!) {
              team(id: $teamId) { id name key issueCount(includeArchived: true) }
            }""";
    private static final String COMMENT = "id body createdAt user { id name email }";
    /** Sin {@code includeArchived} las issues archivadas no vienen. El hueco es lo que se pide de cada comentario. */
    private static final String ISSUES = """
            query TeamIssues($teamId: String!, $after: String) {
              team(id: $teamId) {
                issues(first: 50, after: $after, includeArchived: true, orderBy: createdAt) {
                  nodes {
                    id identifier number title description priority estimate dueDate url
                    createdAt completedAt canceledAt archivedAt
                    state { id name type }
                    assignee { id name email }
                    creator { id name email }
                    parent { id }
                    project { id name }
                    cycle { id number name startsAt endsAt completedAt }
                    labels(first: 20) { nodes { id name } }
                    comments(first: 20) { nodes { %s } pageInfo { hasNextPage endCursor } }
                  }
                  pageInfo { hasNextPage endCursor }
                }
              }
            }""";
    private static final String COMMENTS = """
            query IssueComments($issueId: String!, $after: String) {
              issue(id: $issueId) {
                comments(first: 50, after: $after) { nodes { %s } pageInfo { hasNextPage endCursor } }
              }
            }""".formatted(COMMENT);
    /** Toda issue tiene estado; si alguna llegara sin él, entra con este en vez de con uno sin nombre. */
    private static final String NO_STATE = "Unknown";
    /** La prioridad llega como número: 0 es «sin prioridad». */
    private static final Map<Integer, String> PRIORITIES = Map.of(1, "Urgent", 2, "High", 3, "Medium", 4, "Low");

    private final SourceHttp http;

    @Override
    public ImportSourceType type() {
        return ImportSourceType.LINEAR;
    }

    @Override
    public Discovery discover(SourceConnection connection) {
        Map<String, String> headers = headers(connection);
        JsonNode me = query(headers, VIEWER, null, null).path("viewer");
        List<Found> teams = new ArrayList<>();
        String after = null;
        do {
            JsonNode page = query(headers, TEAMS, null, after).path("teams");
            for (JsonNode team : page.path("nodes")) {
                // Cuántas issues tiene cada equipo se pregunta al leerlo
                teams.add(new Found(text(team, "id"), blankToNull(text(team, "key")), text(team, "name"), -1));
            }
            after = next(page, after);
        } while (after != null);
        return new Discovery(text(me, "name").isBlank() ? text(me, "email") : text(me, "name"), LABEL, teams);
    }

    @Override
    public ImportBundle fetch(SourceConnection connection, List<String> projectIds, boolean withComments, Progress progress) {
        Map<String, String> headers = headers(connection);
        Map<String, JsonNode> teams = new LinkedHashMap<>();
        int total = 0;
        for (String teamId : projectIds) {
            JsonNode team = query(headers, TEAM, Map.of("teamId", teamId), null).path("team");
            if (!team.isObject()) {
                throw new SourceException(SourceHttp.NOT_FOUND);
            }
            teams.put(teamId, team);
            total += Math.max(0, team.path("issueCount").asInt(0));
        }

        // Sin comentarios solo se pide su identificador, para saber cuántos hay
        String issuesQuery = ISSUES.formatted(withComments ? COMMENT : "id");
        Map<String, ExtUser> users = new LinkedHashMap<>();
        List<ExtProject> projects = new ArrayList<>();
        int done = 0;
        for (Map.Entry<String, JsonNode> team : teams.entrySet()) {
            Map<String, ExtSprint> sprints = new LinkedHashMap<>();
            Map<String, ExtModule> modules = new LinkedHashMap<>();
            Map<String, StatusCategory> statuses = new LinkedHashMap<>();
            List<ExtIssue> issues = new ArrayList<>();
            String after = null;
            do {
                JsonNode page = query(headers, issuesQuery, Map.of("teamId", team.getKey()), after).path("team").path("issues");
                for (JsonNode issue : page.path("nodes")) {
                    issues.add(issue(headers, issue, withComments, users, sprints, modules, statuses));
                    done++;
                }
                progress.read(done, Math.max(total, done));
                after = next(page, after);
            } while (after != null);

            // Como se ven en Linear: de la más vieja a la más nueva, y los estados del backlog a lo cancelado
            issues.sort(Comparator.comparing(ExtIssue::createdAt, Comparator.nullsLast(Comparator.naturalOrder())));
            List<ExtStatus> states = new ArrayList<>();
            statuses.forEach((name, category) -> states.add(new ExtStatus(name, category)));
            states.sort(Comparator.comparing(ExtStatus::category));
            List<ExtSprint> cycles = new ArrayList<>(sprints.values());
            cycles.sort(Comparator.comparing(ExtSprint::startDate, Comparator.nullsLast(Comparator.naturalOrder())));
            projects.add(new ExtProject(team.getKey(), blankToNull(text(team.getValue(), "key")), text(team.getValue(), "name"), null,
                    null, states, cycles, List.copyOf(modules.values()), issues));
        }
        return new ImportBundle(projects, List.copyOf(users.values()));
    }

    private ExtIssue issue(Map<String, String> headers, JsonNode issue, boolean withComments, Map<String, ExtUser> users,
                           Map<String, ExtSprint> sprints, Map<String, ExtModule> modules, Map<String, StatusCategory> statuses) {
        String id = text(issue, "id");
        Instant completedAt = instant(text(issue, "completedAt"));
        Instant canceledAt = instant(text(issue, "canceledAt"));
        String status = text(issue.path("state"), "name").isBlank() ? NO_STATE : text(issue.path("state"), "name");
        StatusCategory category = category(text(issue.path("state"), "type"), status, completedAt != null);
        statuses.putIfAbsent(status, category);

        List<String> assignees = new ArrayList<>();
        String assignee = user(users, issue.path("assignee"));
        if (assignee != null) {
            assignees.add(assignee);
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode label : issue.path("labels").path("nodes")) {
            if (!text(label, "name").isBlank() && !labels.contains(text(label, "name"))) {
                labels.add(text(label, "name"));
            }
        }

        List<String> moduleIds = new ArrayList<>();
        String projectId = text(issue.path("project"), "id");
        if (!projectId.isEmpty()) {
            modules.putIfAbsent(projectId, new ExtModule(projectId, text(issue.path("project"), "name"), null, null, null));
            moduleIds.add(projectId);
        }
        List<String> sprintIds = new ArrayList<>();
        JsonNode cycle = issue.path("cycle");
        String cycleId = text(cycle, "id");
        if (!cycleId.isEmpty()) {
            // Un ciclo sin nombre se conoce en Linear por su número
            String name = !text(cycle, "name").isBlank() ? text(cycle, "name")
                    : cycle.path("number").isNumber() ? "Cycle " + (long) cycle.path("number").asDouble() : "Cycle";
            sprints.putIfAbsent(cycleId, new ExtSprint(cycleId, name, date(text(cycle, "startsAt")), date(text(cycle, "endsAt")),
                    instant(text(cycle, "completedAt"))));
            sprintIds.add(cycleId);
        }

        JsonNode inline = issue.path("comments");
        List<ExtComment> comments = new ArrayList<>();
        int commentCount = inline.path("nodes").size();
        if (withComments) {
            inline.path("nodes").forEach(comment -> comments.add(comment(users, comment)));
            // La consulta de issues trae los primeros de cada una: si hay más, se piden aparte
            String after = next(inline, null);
            while (after != null) {
                JsonNode page = query(headers, COMMENTS, Map.of("issueId", id), after).path("issue").path("comments");
                page.path("nodes").forEach(comment -> comments.add(comment(users, comment)));
                after = next(page, after);
            }
            comments.sort(Comparator.comparing(ExtComment::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            commentCount = comments.size();
        }

        JsonNode number = issue.path("number");
        JsonNode priority = issue.path("priority");
        JsonNode estimate = issue.path("estimate");
        return new ExtIssue(id, blankToNull(text(issue, "identifier")), number.isNumber() ? Integer.valueOf((int) number.asDouble()) : null,
                null, Kind.TASK, text(issue, "title"), text(issue, "description"), status, category,
                priority.isNumber() ? PRIORITIES.get((int) priority.asDouble()) : null, assignees, user(users, issue.path("creator")),
                labels, blankToNull(text(issue.path("parent"), "id")), moduleIds, sprintIds, null, date(text(issue, "dueDate")),
                instant(text(issue, "createdAt")), completedAt != null ? completedAt : canceledAt,
                estimate.isNumber() ? Double.valueOf(estimate.asDouble()) : null, null, Map.of(), List.of(), comments, commentCount,
                List.of(), List.of(), blankToNull(text(issue, "url")), !text(issue, "archivedAt").isBlank());
    }

    /** El autor falta cuando el comentario lo dejó una integración. */
    private static ExtComment comment(Map<String, ExtUser> users, JsonNode comment) {
        JsonNode author = comment.path("user");
        return new ExtComment(text(comment, "id"), user(users, author), blankToNull(text(author, "name")), text(comment, "body"),
                instant(text(comment, "createdAt")));
    }

    /** Registra a la persona y devuelve su identificador; {@code null} si el campo viene vacío. */
    private static String user(Map<String, ExtUser> users, JsonNode user) {
        String id = text(user, "id");
        if (id.isEmpty()) {
            return null;
        }
        users.putIfAbsent(id, new ExtUser(id, text(user, "name").isBlank() ? id : text(user, "name"), blankToNull(text(user, "email"))));
        return id;
    }

    /** Linear dice de qué clase es cada estado; si llegara una que no se conoce, se deduce del nombre. */
    private static StatusCategory category(String type, String name, boolean completed) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "triage", "backlog" -> StatusCategory.BACKLOG;
            case "unstarted" -> StatusCategory.TODO;
            case "started" -> StatusCategory.IN_PROGRESS;
            case "completed" -> StatusCategory.DONE;
            case "canceled", "duplicate" -> StatusCategory.CANCELLED;
            default -> CsvImport.category("", name, completed);
        };
    }

    /** El cursor de la página siguiente, o {@code null} si esta es la última (o si el cursor no avanza). */
    private static String next(JsonNode connection, String current) {
        JsonNode info = connection.path("pageInfo");
        String cursor = text(info, "endCursor");
        return info.path("hasNextPage").asBoolean(false) && !cursor.isEmpty() && !cursor.equals(current) ? cursor : null;
    }

    /**
     * Linear responde 200 también cuando la consulta falla: el error viene en {@code errors}. Su texto está en inglés
     * y es para quien programa, así que a la persona se le da el mensaje de siempre según la clase de error.
     */
    private JsonNode query(Map<String, String> headers, String query, Map<String, String> variables, String after) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        Map<String, String> sent = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        if (after != null) {
            sent.put("after", after);
        }
        body.put("variables", sent);
        JsonNode response = http.postJson(endpoint(), headers, body);
        JsonNode errors = response.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            String code = text(errors.get(0).path("extensions"), "code").toUpperCase(Locale.ROOT);
            throw new SourceException(code.contains("AUTH") ? SourceHttp.REJECTED
                    : code.contains("FORBIDDEN") ? SourceHttp.FORBIDDEN
                    : code.contains("NOT_FOUND") ? SourceHttp.NOT_FOUND
                    : code.contains("RATELIMIT") ? SourceHttp.BUSY
                    : SourceHttp.UNREACHABLE);
        }
        return response.path("data");
    }

    @Override
    public byte[] download(SourceConnection connection, ExtAttachment attachment) {
        // No hay archivos que descargar: ver arriba
        throw new SourceException(SourceHttp.UNREACHABLE);
    }

    /** En las pruebas la API es un servidor falso en la propia máquina. */
    String endpoint() {
        return ENDPOINT;
    }

    /** La clave personal va sola en la cabecera, sin «Bearer» (ese prefijo es para los tokens de OAuth). */
    private static Map<String, String> headers(SourceConnection connection) {
        return Map.of("Authorization", credential(connection.token()));
    }
}
