package com.taskflow.api.imports.source;

import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.imports.csv.CsvImport;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;
import com.taskflow.api.imports.model.ImportBundle.ExtComment;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtStatus;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import com.taskflow.api.storage.FileTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.taskflow.api.imports.source.SourceValues.blankToNull;
import static com.taskflow.api.imports.source.SourceValues.credential;
import static com.taskflow.api.imports.source.SourceValues.date;
import static com.taskflow.api.imports.source.SourceValues.encode;
import static com.taskflow.api.imports.source.SourceValues.instant;
import static com.taskflow.api.imports.source.SourceValues.text;

/**
 * Asana por su API REST, con un token de acceso personal. Un proyecto de Asana es un proyecto de TaskFlow, sus
 * secciones son los estados y sus tareas (con las subtareas, que hay que pedir tarea por tarea) son las tareas. Asana
 * no tiene tipos, prioridades ni sprints.
 * <p>
 * En Asana completar una tarea no la cambia de sección, así que una misma sección tiene tareas abiertas y cerradas. Para
 * que un estado no signifique las dos cosas, las completadas entran todas con el estado {@value #COMPLETED} y las
 * demás, con el nombre de su sección.
 * <p>
 * Las listas se piden siempre con {@code limit}: sin él, Asana corta las largas sin avisar.
 */
@Component
@RequiredArgsConstructor
public class AsanaSource implements ImportSource {

    private static final String API = "https://app.asana.com/api/1.0";
    private static final String LABEL = "app.asana.com";
    /** El tope de Asana para una página. */
    private static final int PAGE = 100;
    /** Asana deja anidar subtareas hasta cinco niveles; el tope es por si una respuesta rara hiciera un círculo. */
    private static final int MAX_DEPTH = 10;
    static final String COMPLETED = "Completed";
    /** Como llama Asana a la sección de las tareas que no están en ninguna. */
    static final String NO_SECTION = "Untitled section";
    private static final String TASK_FIELDS = "name,notes,completed,completed_at,created_at,due_on,start_on,assignee,assignee.name,"
            + "memberships.project.name,memberships.section.name,tags.name,parent,num_subtasks,resource_subtype,permalink_url,created_by";

    private final SourceHttp http;

    @Override
    public ImportSourceType type() {
        return ImportSourceType.ASANA;
    }

    @Override
    public Discovery discover(SourceConnection connection) {
        Map<String, String> headers = headers(connection);
        JsonNode me = http.getJson(api() + "/users/me?opt_fields=name,email,workspaces.name", headers).path("data");
        List<Found> projects = new ArrayList<>();
        for (JsonNode workspace : me.path("workspaces")) {
            // Cuántas tareas tiene un proyecto es otra petición por proyecto: se pregunta al leerlo
            for (JsonNode project : list(headers, "/workspaces/" + encode(text(workspace, "gid")) + "/projects?archived=false&opt_fields=name")) {
                projects.add(new Found(text(project, "gid"), null, text(project, "name"), -1));
            }
        }
        return new Discovery(text(me, "name").isBlank() ? text(me, "email") : text(me, "name"), LABEL, projects);
    }

    @Override
    public ImportBundle fetch(SourceConnection connection, List<String> projectIds, boolean withComments, Progress progress) {
        Reading reading = new Reading(headers(connection), withComments, progress);
        Map<String, JsonNode> projectInfo = new LinkedHashMap<>();
        for (String projectId : projectIds) {
            String address = api() + "/projects/" + encode(projectId);
            projectInfo.put(projectId, http.getJson(address + "?opt_fields=name,notes,permalink_url", reading.headers).path("data"));
            reading.total += taskCount(address, reading.headers);
        }
        List<ExtProject> projects = new ArrayList<>();
        projectInfo.forEach((projectId, info) -> projects.add(reading.project(projectId, info)));
        return new ImportBundle(projects, List.copyOf(reading.users.values()));
    }

    /** Cuántas tareas tiene el proyecto, para el avance. Asana limita aparte esta pregunta: si no contesta, se sigue sin saberlo. */
    private int taskCount(String projectAddress, Map<String, String> headers) {
        try {
            return Math.max(0, http.getJson(projectAddress + "/task_counts?opt_fields=num_tasks", headers).path("data").path("num_tasks").asInt(0));
        } catch (SourceException ex) {
            if (SourceHttp.REJECTED.equals(ex.getMessage())) {
                throw ex;
            }
            return 0;
        }
    }

    /** La lectura de una importación: lo que se va juntando entre proyecto y proyecto. */
    private final class Reading {
        private final Map<String, String> headers;
        private final boolean withComments;
        private final Progress progress;
        private final Map<String, ExtUser> users = new LinkedHashMap<>();
        private int done;
        private int total;

        private Reading(Map<String, String> headers, boolean withComments, Progress progress) {
            this.headers = headers;
            this.withComments = withComments;
            this.progress = progress;
        }

        private ExtProject project(String projectId, JsonNode info) {
            List<String> sections = new ArrayList<>();
            for (JsonNode section : list(headers, "/projects/" + encode(projectId) + "/sections?opt_fields=name")) {
                if (!text(section, "name").isBlank() && !sections.contains(text(section, "name"))) {
                    sections.add(text(section, "name"));
                }
            }
            Map<String, ExtIssue> issues = new LinkedHashMap<>();
            for (JsonNode task : list(headers, "/projects/" + encode(projectId) + "/tasks?opt_fields=" + TASK_FIELDS)) {
                task(projectId, task, null, 0, issues);
            }

            // En el orden de Asana: lo que no tiene sección va arriba, y al final lo completado (que allá no es una sección)
            Set<String> used = new LinkedHashSet<>();
            issues.values().forEach(issue -> used.add(issue.status()));
            List<String> names = new ArrayList<>();
            if (used.contains(NO_SECTION)) {
                names.add(NO_SECTION);
            }
            sections.stream().filter(name -> !names.contains(name)).forEach(names::add);
            used.stream().filter(name -> !names.contains(name) && !name.equals(COMPLETED)).forEach(names::add);
            if (used.contains(COMPLETED) && !names.contains(COMPLETED)) {
                names.add(COMPLETED);
            }
            List<ExtStatus> statuses = new ArrayList<>();
            names.forEach(name -> statuses.add(new ExtStatus(name, category(name))));
            return new ExtProject(projectId, null, text(info, "name"), blankToNull(text(info, "notes")),
                    blankToNull(text(info, "permalink_url")), statuses, List.of(), List.of(), List.copyOf(issues.values()));
        }

        /**
         * Una tarea y, debajo, sus subtareas. Una subtarea que además está en el proyecto llega dos veces (en la lista
         * del proyecto y bajo su tarea): entra una sola.
         *
         * @param parentId la tarea bajo la que se encontró; {@code null} si vino en la lista del proyecto
         */
        private void task(String projectId, JsonNode task, String parentId, int depth, Map<String, ExtIssue> issues) {
            String gid = text(task, "gid");
            if (gid.isEmpty() || issues.containsKey(gid)) {
                return;
            }
            String parent = text(task.path("parent"), "gid").isEmpty() ? parentId : text(task.path("parent"), "gid");
            boolean completed = task.path("completed").asBoolean(false);
            String status = completed ? COMPLETED : section(task, projectId);

            List<String> assignees = new ArrayList<>();
            String assignee = person(task.path("assignee"));
            if (assignee != null) {
                assignees.add(assignee);
            }
            List<String> labels = new ArrayList<>();
            for (JsonNode tag : task.path("tags")) {
                if (!text(tag, "name").isBlank() && !labels.contains(text(tag, "name"))) {
                    labels.add(text(tag, "name"));
                }
            }
            List<ExtComment> comments = withComments ? comments(gid) : List.of();
            // Un hito («milestone») es una tarea más
            issues.put(gid, new ExtIssue(gid, null, null, null, parent == null ? Kind.TASK : Kind.SUBTASK, text(task, "name"),
                    text(task, "notes"), status, completed ? StatusCategory.DONE : category(status), null, assignees,
                    person(task.path("created_by")), labels, parent, List.of(), List.of(), date(text(task, "start_on")),
                    date(text(task, "due_on")), instant(text(task, "created_at")), instant(text(task, "completed_at")), null, null,
                    Map.of(), List.of(), comments, comments.size(), attachments(gid), List.of(),
                    blankToNull(text(task, "permalink_url")), false));
            done++;
            progress.read(done, Math.max(total, done));

            // La lista del proyecto no trae las subtareas: se piden a cada tarea que diga tenerlas
            int subtasks = task.path("num_subtasks").asInt(0);
            if (subtasks > 0 && depth < MAX_DEPTH) {
                total += subtasks;
                for (JsonNode subtask : list(headers, "/tasks/" + encode(gid) + "/subtasks?opt_fields=" + TASK_FIELDS)) {
                    task(projectId, subtask, gid, depth + 1, issues);
                }
            }
        }

        /** El nombre de la sección de la tarea en este proyecto (puede estar en varios, con una sección en cada uno). */
        private String section(JsonNode task, String projectId) {
            for (JsonNode membership : task.path("memberships")) {
                if (projectId.equals(text(membership.path("project"), "gid")) && !text(membership.path("section"), "name").isBlank()) {
                    return text(membership.path("section"), "name");
                }
            }
            return NO_SECTION;
        }

        /** Lo que alguien escribió en la tarea; el resto de su historia («la movió», «la asignó») no son comentarios. */
        private List<ExtComment> comments(String taskId) {
            List<ExtComment> comments = new ArrayList<>();
            for (JsonNode story : list(headers, "/tasks/" + encode(taskId)
                    + "/stories?resource_subtype=comment_added&opt_fields=type,resource_subtype,text,created_at,created_by,created_by.name")) {
                if ("comment_added".equals(text(story, "resource_subtype")) || "comment".equals(text(story, "type"))) {
                    JsonNode author = story.path("created_by");
                    String authorId = person(author);
                    comments.add(new ExtComment(text(story, "gid"), authorId,
                            blankToNull(text(author, "name").isBlank() && authorId != null ? users.get(authorId).name() : text(author, "name")),
                            text(story, "text"), instant(text(story, "created_at"))));
                }
            }
            return comments;
        }

        /** Solo los archivos que guarda Asana: los de Drive, Dropbox y demás son enlaces a otra parte. */
        private List<ExtAttachment> attachments(String taskId) {
            List<ExtAttachment> attachments = new ArrayList<>();
            for (JsonNode attachment : list(headers, "/attachments?parent=" + encode(taskId)
                    + "&opt_fields=name,download_url,host,size,resource_subtype")) {
                String kind = text(attachment, "resource_subtype").isBlank() ? text(attachment, "host") : text(attachment, "resource_subtype");
                if ("asana".equals(kind)) {
                    attachments.add(new ExtAttachment(text(attachment, "gid"), text(attachment, "name"),
                            attachment.path("size").isNumber() ? (long) attachment.path("size").asDouble() : 0, null,
                            blankToNull(text(attachment, "download_url"))));
                }
            }
            return attachments;
        }

        /**
         * Registra a la persona y devuelve su identificador; {@code null} si el campo viene vacío. Las tareas solo
         * traen el nombre: el correo (con el que se la reconoce en TaskFlow) se pide una vez por persona.
         */
        private String person(JsonNode user) {
            String gid = text(user, "gid");
            if (gid.isEmpty()) {
                return null;
            }
            if (!users.containsKey(gid)) {
                String name = text(user, "name");
                String email = null;
                try {
                    JsonNode full = http.getJson(api() + "/users/" + encode(gid) + "?opt_fields=name,email", headers).path("data");
                    name = text(full, "name").isBlank() ? name : text(full, "name");
                    email = blankToNull(text(full, "email"));
                } catch (SourceException ex) {
                    // Alguien que ya no está o a quien esta cuenta no puede ver: entra con el nombre que traía la tarea
                    if (!SourceHttp.FORBIDDEN.equals(ex.getMessage()) && !SourceHttp.NOT_FOUND.equals(ex.getMessage())) {
                        throw ex;
                    }
                }
                users.put(gid, new ExtUser(gid, name.isBlank() ? gid : name, email));
            }
            return gid;
        }
    }

    private static StatusCategory category(String section) {
        return CsvImport.category("", section, false);
    }

    /** Todos los elementos de una lista, página por página: la siguiente se pide con el {@code offset} que dio esta. */
    private List<JsonNode> list(Map<String, String> headers, String path) {
        List<JsonNode> items = new ArrayList<>();
        String offset = null;
        while (true) {
            JsonNode page = http.getJson(api() + path + "&limit=" + PAGE + (offset == null ? "" : "&offset=" + encode(offset)), headers);
            page.path("data").forEach(items::add);
            String next = text(page.path("next_page"), "offset");
            if (next.isEmpty() || next.equals(offset)) {
                return items;
            }
            offset = next;
        }
    }

    /**
     * La dirección de descarga que da Asana caduca a los pocos minutos, y entre leer el proyecto y bajar sus adjuntos
     * pasa más que eso: se vuelve a pedir justo antes. Es una dirección ya firmada en otro servidor, así que va sin el
     * token.
     */
    @Override
    public byte[] download(SourceConnection connection, ExtAttachment attachment) {
        JsonNode fresh = http.getJson(api() + "/attachments/" + encode(attachment.id()) + "?opt_fields=name,download_url", headers(connection))
                .path("data");
        String url = text(fresh, "download_url").isBlank() ? attachment.url() : text(fresh, "download_url");
        if (url == null || url.isBlank()) {
            throw new SourceException(SourceHttp.UNREACHABLE);
        }
        return http.download(url, Map.of(), FileTypes.FILE_LIMIT);
    }

    /** En las pruebas la API es un servidor falso en la propia máquina. */
    String api() {
        return API;
    }

    private static Map<String, String> headers(SourceConnection connection) {
        return Map.of("Authorization", "Bearer " + credential(connection.token()));
    }
}
