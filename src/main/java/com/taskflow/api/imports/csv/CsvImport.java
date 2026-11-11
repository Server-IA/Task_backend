package com.taskflow.api.imports.csv;

import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtComment;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtModule;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtSprint;
import com.taskflow.api.imports.model.ImportBundle.ExtStatus;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un archivo CSV de tareas, de cualquier herramienta: primero se muestra qué columnas trae y a qué campo parece
 * corresponder cada una ({@link #preview}); con lo que la persona confirme, se arma el formato común
 * ({@link #toBundle}). Una columna repetida (Jira repite «Labels», «Sprint», «Comment») suma sus valores.
 */
public final class CsvImport {

    /** Cuántos valores de ejemplo se muestran de cada columna. */
    private static final int SAMPLES = 3;
    private static final int SAMPLE_LENGTH = 60;
    private static final Pattern KEY = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)-(\\d+)$");
    /** «Nombre Apellido <correo@x.com>» */
    private static final Pattern NAME_AND_EMAIL = Pattern.compile("^(.*?)\\s*<([^<>\\s]+@[^<>\\s]+)>$");
    /** Un comentario de la exportación de Jira: «14/Oct/26 3:45 PM;persona;texto». */
    private static final Pattern JIRA_COMMENT = Pattern.compile("^([^;]{6,30});([^;]*);(.*)$", Pattern.DOTALL);

    private CsvImport() {
    }

    /**
     * @param samples   los primeros valores no vacíos de la columna
     * @param suggested el campo que se propone, por el nombre de la columna
     */
    public record Column(String name, List<String> samples, CsvField suggested) {
    }

    public record Preview(char delimiter, List<Column> columns, int rows) {
    }

    public static Preview preview(byte[] file) {
        CsvReader.Table table = CsvReader.read(file);
        List<Column> columns = new ArrayList<>();
        Set<CsvField> single = new LinkedHashSet<>();
        for (int index = 0; index < table.header().size(); index++) {
            List<String> samples = new ArrayList<>();
            for (List<String> row : table.rows()) {
                String value = row.get(index).strip();
                if (!value.isEmpty() && samples.size() < SAMPLES && !samples.contains(cut(value))) {
                    samples.add(cut(value));
                }
            }
            CsvField suggested = CsvField.suggest(table.header().get(index));
            // Dos columnas distintas no pueden ser las dos el título o el estado; las que sí se repiten, suman
            if (!repeats(suggested) && !single.add(suggested)) {
                suggested = CsvField.IGNORE;
            }
            columns.add(new Column(table.header().get(index), samples, suggested));
        }
        return new Preview(table.delimiter(), columns, table.rows().size());
    }

    private static boolean repeats(CsvField field) {
        return field == CsvField.IGNORE || field == CsvField.LABELS || field == CsvField.SPRINT || field == CsvField.COMMENT
                || field == CsvField.ASSIGNEE;
    }

    private static String cut(String value) {
        String line = value.replaceAll("\\s+", " ");
        return line.length() > SAMPLE_LENGTH ? line.substring(0, SAMPLE_LENGTH - 1) + "…" : line;
    }

    /**
     * @param fields      el campo de cada columna, en su orden (las que falten se ignoran)
     * @param projectName el nombre del proyecto, cuando el archivo no lo trae en una columna
     */
    public static ImportBundle toBundle(byte[] file, List<CsvField> fields, String projectName) {
        CsvReader.Table table = CsvReader.read(file);
        Rows rows = new Rows(table, fields);
        CsvDates dates = CsvDates.forValues(rows.allValues(CsvField.DUE_DATE, CsvField.START_DATE, CsvField.CREATED_AT,
                CsvField.COMPLETED_AT));

        Map<String, ExtUser> users = new LinkedHashMap<>();
        Map<String, ProjectDraft> projects = new LinkedHashMap<>();
        for (int index = 0; index < table.rows().size(); index++) {
            String title = rows.first(index, CsvField.TITLE);
            if (title.isBlank()) {
                continue;
            }
            String key = rows.first(index, CsvField.KEY);
            Matcher keyMatcher = KEY.matcher(key);
            String projectKey = rows.first(index, CsvField.PROJECT_KEY);
            String projectTitle = rows.first(index, CsvField.PROJECT_NAME);
            String projectId = !projectKey.isBlank() ? projectKey : !projectTitle.isBlank() ? projectTitle : "csv";
            ProjectDraft project = projects.computeIfAbsent(projectId, id -> new ProjectDraft(id,
                    !projectKey.isBlank() ? projectKey : null, !projectTitle.isBlank() ? projectTitle : projectName));
            if (project.key == null && keyMatcher.matches()) {
                project.keyPrefixes.add(keyMatcher.group(1).toUpperCase(Locale.ROOT));
            }

            String status = rows.first(index, CsvField.STATUS);
            Instant completedAt = dates.instant(rows.first(index, CsvField.COMPLETED_AT));
            StatusCategory category = category(rows.first(index, CsvField.STATUS_CATEGORY), status, completedAt != null);
            if (status.isBlank()) {
                status = defaultStatus(category);
            }
            project.statuses.putIfAbsent(status, category);

            String type = rows.first(index, CsvField.TYPE);
            List<String> assignees = new ArrayList<>();
            for (String person : people(rows.all(index, CsvField.ASSIGNEE))) {
                assignees.add(user(users, person));
            }
            String creator = rows.first(index, CsvField.CREATOR);
            List<String> sprints = new ArrayList<>();
            for (String sprint : rows.all(index, CsvField.SPRINT)) {
                sprints.add(project.sprint(sprint));
            }
            String module = rows.first(index, CsvField.MODULE);
            List<ExtComment> comments = new ArrayList<>();
            for (String comment : rows.all(index, CsvField.COMMENT)) {
                comments.add(comment(comment, comments.size(), users, dates));
            }
            String id = !key.isBlank() ? key : "fila-" + (index + 2);
            project.issues.add(new Draft(id, key.isBlank() ? null : key,
                    keyMatcher.matches() ? Integer.valueOf(keyMatcher.group(2)) : null,
                    type.isBlank() ? null : type, kind(type), title.strip(), rows.first(index, CsvField.DESCRIPTION).strip(),
                    status, category, blankToNull(rows.first(index, CsvField.PRIORITY)), assignees,
                    creator.isBlank() ? null : user(users, creator), labels(rows.all(index, CsvField.LABELS)),
                    rows.first(index, CsvField.PARENT).strip(), module.isBlank() ? List.of() : List.of(project.module(module)),
                    sprints, dates.date(rows.first(index, CsvField.START_DATE)), dates.date(rows.first(index, CsvField.DUE_DATE)),
                    dates.instant(rows.first(index, CsvField.CREATED_AT)), completedAt, points(rows.first(index, CsvField.POINTS)),
                    comments, blankToNull(rows.first(index, CsvField.URL))));
        }

        List<ExtProject> result = new ArrayList<>();
        for (ProjectDraft project : projects.values()) {
            result.add(project.build());
        }
        return new ImportBundle(result, List.copyOf(users.values()));
    }

    /** La categoría de un estado: la que diga el archivo o, si no, la que sugiere su nombre. */
    static StatusCategory category(String declared, String status, boolean completed) {
        String hint = CsvField.normalize(declared);
        if (hint.equals("done") || hint.equals("listo") || hint.equals("finalizado")) {
            return StatusCategory.DONE;
        }
        if (hint.equals("in progress") || hint.equals("en curso") || hint.equals("en progreso")) {
            return StatusCategory.IN_PROGRESS;
        }
        if (hint.equals("to do") || hint.equals("por hacer")) {
            return StatusCategory.TODO;
        }
        String name = CsvField.normalize(status);
        if (matches(name, "cancel", "won't", "wont", "descart", "rechaz", "duplicate", "duplicad")) {
            return StatusCategory.CANCELLED;
        }
        if (matches(name, "done", "complete", "closed", "resolved", "hech", "terminad", "completad", "list", "finaliz", "cerrad",
                "resuelt", "shipped", "released")) {
            return StatusCategory.DONE;
        }
        if (matches(name, "progress", "doing", "review", "curso", "proceso", "qa", "test", "prueba", "revision", "started",
                "desarrollo", "development", "blocked", "bloquead")) {
            return StatusCategory.IN_PROGRESS;
        }
        if (matches(name, "backlog", "pendiente", "icebox", "ideas", "triage", "someday")) {
            return StatusCategory.BACKLOG;
        }
        return completed ? StatusCategory.DONE : StatusCategory.TODO;
    }

    private static String defaultStatus(StatusCategory category) {
        return switch (category) {
            case DONE -> "Done";
            case CANCELLED -> "Cancelled";
            case IN_PROGRESS -> "In Progress";
            case BACKLOG -> "Backlog";
            case TODO -> "Todo";
        };
    }

    /** Lo que un tipo es, por su nombre. */
    public static Kind kind(String type) {
        String name = CsvField.normalize(type);
        if (matches(name, "epic", "epica", "initiative")) {
            return Kind.EPIC;
        }
        if (matches(name, "sub-task", "subtask", "subtarea", "sub task")) {
            return Kind.SUBTASK;
        }
        if (matches(name, "bug", "error", "defect", "incident", "fallo", "defecto")) {
            return Kind.BUG;
        }
        if (matches(name, "story", "historia", "feature")) {
            return Kind.STORY;
        }
        return Kind.TASK;
    }

    private static boolean matches(String name, String... parts) {
        for (String part : parts) {
            if (name.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** Varias personas en una celda van separadas por punto y coma, barra vertical o, si todas son correos, coma. */
    private static List<String> people(List<String> cells) {
        List<String> result = new ArrayList<>();
        for (String cell : cells) {
            String[] parts = cell.split("[;|]");
            if (parts.length == 1 && cell.chars().filter(c -> c == '@').count() > 1) {
                parts = cell.split(",");
            }
            for (String part : parts) {
                if (!part.isBlank()) {
                    result.add(part.strip());
                }
            }
        }
        return result;
    }

    private static List<String> labels(List<String> cells) {
        Set<String> result = new LinkedHashSet<>();
        for (String cell : cells) {
            for (String part : cell.replaceAll("^\\[|]$", "").split("[,;|]")) {
                if (!part.isBlank()) {
                    result.add(part.strip());
                }
            }
        }
        return List.copyOf(result);
    }

    /** Registra a la persona (si no estaba) y devuelve su identificador: su correo o su nombre, en minúsculas. */
    private static String user(Map<String, ExtUser> users, String text) {
        Matcher matcher = NAME_AND_EMAIL.matcher(text.strip());
        String name = matcher.matches() ? matcher.group(1).strip() : text.strip();
        String email = matcher.matches() ? matcher.group(2) : name.contains("@") && !name.contains(" ") ? name : null;
        if (name.isEmpty() && email != null) {
            name = email;
        }
        String id = (email != null ? email : name).toLowerCase(Locale.ROOT);
        users.putIfAbsent(id, new ExtUser(id, name, email == null ? null : email.toLowerCase(Locale.ROOT)));
        return id;
    }

    private static ExtComment comment(String text, int index, Map<String, ExtUser> users, CsvDates dates) {
        Matcher matcher = JIRA_COMMENT.matcher(text.strip());
        if (matcher.matches()) {
            Instant when = dates.instant(matcher.group(1));
            if (when != null) {
                String author = matcher.group(2).strip();
                String authorId = author.isEmpty() ? null : user(users, author);
                return new ExtComment(String.valueOf(index), authorId, author.isEmpty() ? null : author, matcher.group(3).strip(), when);
            }
        }
        return new ExtComment(String.valueOf(index), null, null, text.strip(), null);
    }

    private static Double points(String text) {
        try {
            return text.isBlank() ? null : Double.valueOf(text.strip().replace(',', '.'));
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }

    private static String blankToNull(String text) {
        return text.isBlank() ? null : text.strip();
    }

    /** Una tarea leída, a la espera de saber quién es su padre (que puede venir más abajo en el archivo). */
    private record Draft(String id, String key, Integer number, String type, Kind kind, String title, String description,
                         String status, StatusCategory category, String priority, List<String> assignees, String creator,
                         List<String> labels, String parent, List<String> modules, List<String> sprints,
                         java.time.LocalDate startDate, java.time.LocalDate dueDate, Instant createdAt, Instant completedAt,
                         Double points, List<ExtComment> comments, String url) {
    }

    private static final class ProjectDraft {
        private final String id;
        private final String key;
        private final String name;
        private final Set<String> keyPrefixes = new LinkedHashSet<>();
        private final Map<String, StatusCategory> statuses = new LinkedHashMap<>();
        private final Map<String, ExtSprint> sprints = new LinkedHashMap<>();
        private final Map<String, ExtModule> modules = new LinkedHashMap<>();
        private final List<Draft> issues = new ArrayList<>();

        private ProjectDraft(String id, String key, String name) {
            this.id = id;
            this.key = key;
            this.name = name;
        }

        private String sprint(String name) {
            String sprintId = name.strip();
            sprints.putIfAbsent(sprintId, new ExtSprint(sprintId, sprintId, null, null, null));
            return sprintId;
        }

        private String module(String name) {
            String moduleId = name.strip();
            modules.putIfAbsent(moduleId, new ExtModule(moduleId, moduleId, null, null, null));
            return moduleId;
        }

        private ExtProject build() {
            // El padre se nombra por su código o, si no, por su título (cuando solo una tarea lo lleva)
            Map<String, String> byKey = new HashMap<>();
            Map<String, String> byTitle = new HashMap<>();
            Set<String> repeatedTitles = new LinkedHashSet<>();
            for (Draft draft : issues) {
                byKey.put(draft.id.toLowerCase(Locale.ROOT), draft.id);
                if (byTitle.put(draft.title.toLowerCase(Locale.ROOT), draft.id) != null) {
                    repeatedTitles.add(draft.title.toLowerCase(Locale.ROOT));
                }
            }
            List<ExtIssue> result = new ArrayList<>();
            for (Draft draft : issues) {
                String parent = draft.parent.toLowerCase(Locale.ROOT);
                String parentId = parent.isEmpty() ? null
                        : byKey.containsKey(parent) ? byKey.get(parent)
                        : repeatedTitles.contains(parent) ? null : byTitle.get(parent);
                if (draft.id.equals(parentId)) {
                    parentId = null;
                }
                result.add(new ExtIssue(draft.id, draft.key, draft.number, draft.type, draft.kind, draft.title, draft.description,
                        draft.status, draft.category, draft.priority, draft.assignees, draft.creator, draft.labels, parentId,
                        draft.modules, draft.sprints, draft.startDate, draft.dueDate, draft.createdAt, draft.completedAt,
                        draft.points, null, Map.of(), List.of(), draft.comments, draft.comments.size(), List.of(), List.of(),
                        draft.url, false));
            }
            List<ExtStatus> states = new ArrayList<>();
            statuses.forEach((name, category) -> states.add(new ExtStatus(name, category)));
            // Sin columna de proyecto, la clave es el prefijo de los códigos si todos comparten uno (PORT-1, PORT-2…)
            String projectKey = key != null ? key : keyPrefixes.size() == 1 ? keyPrefixes.iterator().next() : null;
            return new ExtProject(id, projectKey, name, null, null, states, List.copyOf(sprints.values()),
                    List.copyOf(modules.values()), result);
        }
    }

    /** Las filas del archivo, vistas por campo en vez de por columna. */
    private static final class Rows {
        private final CsvReader.Table table;
        private final Map<CsvField, List<Integer>> columns = new HashMap<>();

        private Rows(CsvReader.Table table, List<CsvField> fields) {
            this.table = table;
            for (int index = 0; index < table.header().size(); index++) {
                CsvField field = index < fields.size() && fields.get(index) != null ? fields.get(index) : CsvField.IGNORE;
                if (field != CsvField.IGNORE) {
                    columns.computeIfAbsent(field, key -> new ArrayList<>()).add(index);
                }
            }
        }

        /** El primer valor no vacío de la fila para ese campo, o cadena vacía. */
        private String first(int row, CsvField field) {
            List<String> values = all(row, field);
            return values.isEmpty() ? "" : values.get(0);
        }

        private List<String> all(int row, CsvField field) {
            List<String> values = new ArrayList<>();
            for (int column : columns.getOrDefault(field, List.of())) {
                String value = table.rows().get(row).get(column);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        }

        private List<String> allValues(CsvField... fields) {
            List<String> values = new ArrayList<>();
            for (int row = 0; row < table.rows().size(); row++) {
                for (CsvField field : fields) {
                    values.addAll(all(row, field));
                }
            }
            return values;
        }
    }
}
