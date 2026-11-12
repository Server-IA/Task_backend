package com.taskflow.api.imports.csv;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * A qué campo de una tarea va una columna de un archivo CSV. Cada campo conoce los nombres con los que las
 * herramientas más usadas (Jira, Trello, Linear, Asana, ClickUp) y una hoja de cálculo en español o inglés suelen
 * llamar a esa columna, para proponerlo solo.
 */
public enum CsvField {
    IGNORE,
    TITLE("summary", "title", "name", "task name", "nombre", "titulo", "asunto", "tarea", "card name", "nombre de la tarea"),
    DESCRIPTION("description", "descripcion", "notes", "notas", "task content", "card description", "detalle", "desc"),
    STATUS("status", "estado", "state", "list", "list name", "section", "section/column", "columna", "seccion", "lista"),
    /** «To Do», «In Progress» o «Done»: la categoría del estado, si el archivo la trae (Jira). */
    STATUS_CATEGORY("status category", "categoria del estado"),
    TYPE("issue type", "type", "tipo", "issuetype", "task type", "tipo de incidencia", "tipo de tarea"),
    PRIORITY("priority", "prioridad"),
    ASSIGNEE("assignee", "assignees", "asignado", "asignados", "asignada", "responsable", "assigned to", "members",
            "assignee email", "persona asignada"),
    CREATOR("reporter", "creator", "created by", "creado por", "creada por", "informador", "author", "autor"),
    LABELS("labels", "label", "tags", "tag", "etiquetas", "etiqueta"),
    START_DATE("start date", "start", "fecha de inicio", "inicio", "start on", "started", "started at"),
    DUE_DATE("due date", "due", "fecha limite", "vencimiento", "due on", "deadline", "fecha de entrega", "fecha de vencimiento"),
    CREATED_AT("created", "created at", "date created", "creado", "creada", "fecha de creacion", "created on"),
    COMPLETED_AT("resolved", "completed at", "completed", "date done", "date closed", "closed", "fecha de cierre", "resuelta",
            "completed on", "resolution date", "fecha de resolucion", "completada"),
    /** El código o identificador de la tarea en el origen («PORT-123»). */
    KEY("issue key", "key", "id", "task id", "identifier", "codigo", "card id", "clave", "clave de incidencia", "issue id"),
    /** La tarea de la que cuelga: su código, su identificador o su título. */
    PARENT("parent", "parent id", "parent key", "parent task", "parent issue", "parent name", "tarea padre", "padre",
            "principal", "parent issue key"),
    SPRINT("sprint", "cycle", "ciclo", "cycle name", "iteration", "iteracion"),
    MODULE("epic", "epic name", "epic link", "modulo", "epica", "epic link summary", "initiative"),
    COMMENT("comment", "comments", "comentario", "comentarios"),
    /** Story points o estimación: TaskFlow no los lleva; quedan anotados en la descripción. */
    POINTS("story points", "estimate", "points", "time estimate", "estimacion", "puntos", "puntos de historia"),
    URL("url", "link", "permalink", "enlace", "permalink url"),
    PROJECT_KEY("project key", "clave del proyecto"),
    PROJECT_NAME("project name", "nombre del proyecto");

    private final List<String> names;

    CsvField(String... names) {
        this.names = List.of(names);
    }

    /** El campo que se propone para una columna con ese nombre; {@link #IGNORE} si no se reconoce. */
    public static CsvField suggest(String header) {
        String name = normalize(header);
        // «Custom field (Story Points)» en Jira
        if (name.startsWith("custom field (") && name.endsWith(")")) {
            name = name.substring("custom field (".length(), name.length() - 1);
        }
        for (CsvField field : values()) {
            if (field.names.contains(name)) {
                return field;
            }
        }
        return IGNORE;
    }

    /** En minúsculas, sin tildes y con los espacios juntos: «Fecha Límite » → «fecha limite». */
    public static String normalize(String text) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ").strip();
    }
}
