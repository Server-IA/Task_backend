package com.taskflow.api.imports.source;

import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;
import com.taskflow.api.imports.model.ImportBundle.ExtComment;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtLink;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtSprint;
import com.taskflow.api.imports.model.ImportBundle.ExtStatus;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.LinkType;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import com.taskflow.api.imports.text.AdfToMarkdown;
import com.taskflow.api.storage.FileTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Jira Cloud por su API REST v3, con el correo de la cuenta y un token de API. Lee los proyectos y, de los elegidos,
 * todas sus incidencias con la búsqueda por páginas ({@code /search/jql}; la antigua {@code /search} ya no existe),
 * sus comentarios, adjuntos, sprints y enlaces.
 * <p>
 * Solo acepta sitios {@code *.atlassian.net}: Jira Server y Data Center no hablan esta API (de ellos se importa con su
 * exportación CSV), y así la dirección que escribe la persona no puede apuntar a otra parte.
 */
@Component
@RequiredArgsConstructor
public class JiraSource implements ImportSource {

    public static final String BAD_SITE = "La dirección de Jira debe ser como https://miempresa.atlassian.net";

    private static final int PAGE = 100;
    private static final String SPRINT_FIELD = "com.pyxis.greenhopper.jira:gh-sprint";
    private static final String POINTS_FIELD = "com.pyxis.greenhopper.jira:jsw-story-points";
    private static final Set<String> POINTS_NAMES = Set.of("story points", "story point estimate");
    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final List<String> FIELDS = List.of("summary", "description", "issuetype", "status", "priority", "assignee",
            "reporter", "labels", "components", "parent", "created", "resolutiondate", "duedate", "attachment", "comment",
            "issuelinks", "timetracking");

    private final SourceHttp http;

    @Override
    public ImportSourceType type() {
        return ImportSourceType.JIRA;
    }

    @Override
    public Discovery discover(SourceConnection connection) {
        String base = site(connection);
        Map<String, String> headers = headers(connection);
        JsonNode me = http.getJson(base + "/rest/api/3/myself", headers);
        List<Found> projects = new ArrayList<>();
        for (int start = 0; ; start += PAGE) {
            JsonNode page = http.getJson(base + "/rest/api/3/project/search?expand=insight&maxResults=" + PAGE + "&startAt=" + start, headers);
            for (JsonNode project : page.path("values")) {
                JsonNode count = project.path("insight").path("totalIssueCount");
                projects.add(new Found(text(project, "id"), text(project, "key"), text(project, "name"),
                        count.isNumber() ? count.asInt() : count(base, headers, text(project, "id"))));
            }
            if (page.path("isLast").asBoolean(true) || page.path("values").isEmpty()) {
                break;
            }
        }
        return new Discovery(text(me, "displayName"), URI.create(base).getHost(), projects);
    }

    private int count(String base, Map<String, String> headers, String projectId) {
        JsonNode result = http.postJson(base + "/rest/api/3/search/approximate-count", headers, Map.of("jql", "project = " + projectId));
        return result.path("count").asInt(-1);
    }

    @Override
    public ImportBundle fetch(SourceConnection connection, List<String> projectIds, boolean withComments, Progress progress) {
        String base = site(connection);
        Map<String, String> headers = headers(connection);

        // Los sprints y los story points van en campos personalizados cuyo identificador cambia de un sitio a otro
        List<String> sprintFields = new ArrayList<>();
        List<String> pointFields = new ArrayList<>();
        for (JsonNode field : http.getJson(base + "/rest/api/3/field", headers)) {
            String custom = text(field.path("schema"), "custom");
            if (SPRINT_FIELD.equals(custom)) {
                sprintFields.add(text(field, "id"));
            } else if (POINTS_FIELD.equals(custom) || POINTS_NAMES.contains(text(field, "name").toLowerCase(Locale.ROOT))) {
                pointFields.add(text(field, "id"));
            }
        }
        List<String> fields = new ArrayList<>(FIELDS);
        fields.addAll(sprintFields);
        fields.addAll(pointFields);

        Map<String, JsonNode> projectInfo = new LinkedHashMap<>();
        int total = 0;
        for (String projectId : projectIds) {
            JsonNode project = http.getJson(base + "/rest/api/3/project/" + encode(projectId), headers);
            projectInfo.put(projectId, project);
            total += Math.max(0, count(base, headers, projectId));
        }

        Map<String, ExtUser> users = new LinkedHashMap<>();
        List<ExtProject> projects = new ArrayList<>();
        int done = 0;
        for (String projectId : projectIds) {
            JsonNode info = projectInfo.get(projectId);
            Map<String, ExtSprint> sprints = new LinkedHashMap<>();
            Map<String, StatusCategory> statuses = new LinkedHashMap<>();
            List<ExtIssue> issues = new ArrayList<>();
            String token = null;
            do {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("jql", "project = " + projectId + " ORDER BY created ASC");
                body.put("maxResults", PAGE);
                body.put("fields", fields);
                if (token != null) {
                    body.put("nextPageToken", token);
                }
                JsonNode page = http.postJson(base + "/rest/api/3/search/jql", headers, body);
                for (JsonNode issue : page.path("issues")) {
                    issues.add(issue(base, headers, issue, sprintFields, pointFields, withComments, users, sprints, statuses));
                    done++;
                }
                progress.read(Math.min(done, Math.max(total, done)), Math.max(total, done));
                token = page.path("nextPageToken").isString() ? page.path("nextPageToken").stringValue() : null;
            } while (token != null);
            List<ExtStatus> states = new ArrayList<>();
            statuses.forEach((name, category) -> states.add(new ExtStatus(name, category)));
            projects.add(new ExtProject(projectId, text(info, "key"), text(info, "name"), AdfToMarkdown.convert(info.path("description")),
                    base + "/browse/" + text(info, "key"), states, List.copyOf(sprints.values()), List.of(), issues));
        }
        return new ImportBundle(projects, List.copyOf(users.values()));
    }

    private ExtIssue issue(String base, Map<String, String> headers, JsonNode issue, List<String> sprintFields, List<String> pointFields,
                           boolean withComments, Map<String, ExtUser> users, Map<String, ExtSprint> sprints,
                           Map<String, StatusCategory> statuses) {
        JsonNode fields = issue.path("fields");
        String id = text(issue, "id");
        String key = text(issue, "key");
        JsonNode type = fields.path("issuetype");
        String status = text(fields.path("status"), "name");
        StatusCategory category = category(text(fields.path("status").path("statusCategory"), "key"), status);
        statuses.putIfAbsent(status, category);

        List<String> assignees = new ArrayList<>();
        String assignee = user(users, fields.path("assignee"));
        if (assignee != null) {
            assignees.add(assignee);
        }
        Set<String> labels = new LinkedHashSet<>();
        fields.path("labels").forEach(label -> labels.add(label.asString("")));
        fields.path("components").forEach(component -> labels.add(text(component, "name")));
        labels.remove("");

        List<String> sprintIds = new ArrayList<>();
        for (String field : sprintFields) {
            for (JsonNode sprint : fields.path(field)) {
                // El formato viejo (una cadena) ya no se usa; si llegara, se ignora
                if (!sprint.isObject()) {
                    continue;
                }
                String sprintId = text(sprint, "id");
                Instant completed = instant(text(sprint, "completeDate"));
                if (completed == null && "closed".equalsIgnoreCase(text(sprint, "state"))) {
                    completed = instant(text(sprint, "endDate"));
                    if (completed == null) {
                        completed = Instant.now();
                    }
                }
                sprints.put(sprintId, new ExtSprint(sprintId, text(sprint, "name"), date(text(sprint, "startDate")),
                        date(text(sprint, "endDate")), completed));
                sprintIds.add(sprintId);
            }
        }
        Double points = null;
        for (String field : pointFields) {
            if (fields.path(field).isNumber()) {
                points = fields.path(field).asDouble();
            }
        }
        JsonNode spent = fields.path("timetracking").path("timeSpentSeconds");

        List<ExtAttachment> attachments = new ArrayList<>();
        for (JsonNode attachment : fields.path("attachment")) {
            String attachmentId = attachment.path("id").asString("");
            attachments.add(new ExtAttachment(attachmentId, text(attachment, "filename"), attachment.path("size").asLong(0),
                    text(attachment, "mimeType"), base + "/rest/api/3/attachment/content/" + attachmentId));
        }

        // La búsqueda trae solo los primeros comentarios de cada incidencia: si faltan, se piden aparte
        JsonNode commentField = fields.path("comment");
        JsonNode inline = commentField.isArray() ? commentField : commentField.path("comments");
        int commentTotal = commentField.isArray() ? commentField.size() : commentField.path("total").asInt(inline.size());
        List<ExtComment> comments = new ArrayList<>();
        if (withComments) {
            if (commentTotal > inline.size()) {
                for (int start = 0; start < commentTotal; ) {
                    JsonNode page = http.getJson(base + "/rest/api/3/issue/" + id + "/comment?maxResults=" + PAGE + "&startAt=" + start, headers);
                    int before = comments.size();
                    page.path("comments").forEach(comment -> comments.add(comment(users, comment)));
                    if (comments.size() == before) {
                        break;
                    }
                    start += comments.size() - before;
                }
            } else {
                inline.forEach(comment -> comments.add(comment(users, comment)));
            }
        }

        List<ExtLink> links = new ArrayList<>();
        for (JsonNode link : fields.path("issuelinks")) {
            String name = (text(link.path("type"), "name") + " " + text(link.path("type"), "outward")).toLowerCase(Locale.ROOT);
            boolean outward = link.has("outwardIssue");
            String other = text(link.path(outward ? "outwardIssue" : "inwardIssue"), "id");
            if (other.isEmpty()) {
                continue;
            }
            LinkType linkType = name.contains("block") ? (outward ? LinkType.BLOCKS : LinkType.BLOCKED_BY)
                    : name.contains("duplicat") && outward ? LinkType.DUPLICATES : LinkType.RELATES;
            links.add(new ExtLink(linkType, other));
        }

        Integer number = null;
        int dash = key.lastIndexOf('-');
        if (dash > 0) {
            try {
                number = Integer.valueOf(key.substring(dash + 1));
            } catch (NumberFormatException notNumber) {
                // sin número
            }
        }
        return new ExtIssue(id, key, number, text(type, "name"), kind(type), text(fields, "summary"),
                AdfToMarkdown.convert(fields.path("description")), status, category, blankToNull(text(fields.path("priority"), "name")),
                assignees, user(users, fields.path("reporter")), List.copyOf(labels), blankToNull(text(fields.path("parent"), "id")),
                List.of(), sprintIds, null, date(text(fields, "duedate")), instant(text(fields, "created")),
                instant(text(fields, "resolutiondate")), points, spent.isNumber() ? spent.asLong() : null, Map.of(), List.of(),
                comments, commentTotal, attachments, links, base + "/browse/" + key, false);
    }

    private static ExtComment comment(Map<String, ExtUser> users, JsonNode comment) {
        JsonNode author = comment.path("author");
        return new ExtComment(text(comment, "id"), user(users, author), blankToNull(text(author, "displayName")),
                AdfToMarkdown.convert(comment.path("body")), instant(text(comment, "created")));
    }

    /** Registra a la persona y devuelve su identificador; {@code null} si el campo viene vacío. */
    private static String user(Map<String, ExtUser> users, JsonNode user) {
        String id = text(user, "accountId");
        if (id.isEmpty()) {
            return null;
        }
        // El correo solo viene si la persona lo dejó visible en su perfil
        users.putIfAbsent(id, new ExtUser(id, text(user, "displayName"), blankToNull(text(user, "emailAddress"))));
        return id;
    }

    private static Kind kind(JsonNode type) {
        if (type.path("subtask").asBoolean(false) || type.path("hierarchyLevel").asInt(0) < 0) {
            return Kind.SUBTASK;
        }
        if (type.path("hierarchyLevel").asInt(0) >= 1) {
            return Kind.EPIC;
        }
        String name = text(type, "name").toLowerCase(Locale.ROOT);
        if (name.contains("bug") || name.contains("error") || name.contains("defect")) {
            return Kind.BUG;
        }
        return name.contains("story") || name.contains("historia") ? Kind.STORY : Kind.TASK;
    }

    /** La categoría de Jira dice si algo está por hacer, en curso o listo; el nombre afina los dos extremos. */
    private static StatusCategory category(String key, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "done" -> lower.contains("cancel") || lower.contains("won't") || lower.contains("wont") || lower.contains("descart")
                    || lower.contains("rechaz") ? StatusCategory.CANCELLED : StatusCategory.DONE;
            case "indeterminate" -> StatusCategory.IN_PROGRESS;
            default -> lower.contains("backlog") ? StatusCategory.BACKLOG : StatusCategory.TODO;
        };
    }

    @Override
    public byte[] download(SourceConnection connection, ExtAttachment attachment) {
        return http.download(attachment.url(), headers(connection), FileTypes.FILE_LIMIT);
    }

    /** La dirección del sitio, limpia: «miempresa.atlassian.net/jira/…» → «https://miempresa.atlassian.net». */
    String site(SourceConnection connection) {
        String raw = connection.baseUrl() == null ? "" : connection.baseUrl().strip();
        if (raw.isEmpty()) {
            throw new SourceException(BAD_SITE);
        }
        try {
            URI uri = URI.create(raw.contains("://") ? raw : "https://" + raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!allowed(uri, host)) {
                throw new SourceException(BAD_SITE);
            }
            return uri.getScheme() + "://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException ex) {
            throw new SourceException(BAD_SITE, ex);
        }
    }

    /** En las pruebas el «sitio» es un servidor falso en la propia máquina. */
    boolean allowed(URI uri, String host) {
        return "https".equalsIgnoreCase(uri.getScheme()) && host.endsWith(".atlassian.net") && host.length() > ".atlassian.net".length();
    }

    private static Map<String, String> headers(SourceConnection connection) {
        String pair = (connection.email() == null ? "" : connection.email().strip()) + ":" + (connection.token() == null ? "" : connection.token().strip());
        return Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Instant instant(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text, JIRA_TIME).toInstant();
        } catch (DateTimeParseException notJira) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException notIso) {
                return null;
            }
        }
    }

    private static LocalDate date(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException notDate) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : value.isNumber() ? value.asString() : "";
    }

    private static String blankToNull(String text) {
        return text.isBlank() ? null : text;
    }
}
