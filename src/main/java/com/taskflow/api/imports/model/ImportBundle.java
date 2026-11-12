package com.taskflow.api.imports.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Lo que se leyó de la herramienta de origen (Jira, Trello, Linear, Asana o un archivo), ya en un formato común: cada
 * origen tiene un adaptador que arma esto y un solo importador lo escribe en TaskFlow. Todo va con los identificadores
 * y los nombres del origen; qué estado, tipo o persona de TaskFlow le corresponde a cada uno lo dicen las
 * equivalencias, no este formato.
 * <p>
 * Se guarda como JSON mientras dura la importación (entre leer el origen y escribir en TaskFlow), así que solo lleva
 * tipos que Jackson lee y escribe sin ayuda.
 *
 * @param projects los proyectos elegidos, con todo su contenido
 * @param users    las personas que aparecen en ellos (asignadas, autoras de tareas o de comentarios)
 */
public record ImportBundle(List<ExtProject> projects, List<ExtUser> users) {

    /**
     * @param id    identificador en el origen
     * @param email puede faltar: Jira lo oculta según la privacidad de cada cuenta
     */
    public record ExtUser(String id, String name, String email) {
    }

    /**
     * @param key      clave corta del proyecto en el origen (la de Jira, «PORT»); sin ella se arma una del nombre
     * @param statuses los estados del origen, en el orden en que se ven allá
     * @param sprints  sprints o ciclos
     * @param modules  agrupaciones que no son tareas (proyectos de Linear, por ejemplo); las épicas de Jira llegan como
     *                 tareas de tipo {@link Kind#EPIC} y el importador decide si son módulos
     */
    public record ExtProject(String id, String key, String name, String description, String url, List<ExtStatus> statuses,
                             List<ExtSprint> sprints, List<ExtModule> modules, List<ExtIssue> issues) {
    }

    /** En qué va una tarea, en los tres grupos que entiende cualquier herramienta, más los dos extremos. */
    public enum StatusCategory {
        BACKLOG, TODO, IN_PROGRESS, DONE, CANCELLED
    }

    public record ExtStatus(String name, StatusCategory category) {
    }

    /**
     * @param completedAt cuándo se cerró; {@code null} si sigue abierto o no ha empezado
     */
    public record ExtSprint(String id, String name, LocalDate startDate, LocalDate endDate, Instant completedAt) {
    }

    public record ExtModule(String id, String name, String description, LocalDate startDate, LocalDate targetDate) {
    }

    /** Qué es una tarea en el origen, para proponer su tipo en TaskFlow. */
    public enum Kind {
        EPIC, STORY, TASK, BUG, SUBTASK
    }

    /**
     * @param key           el código con el que se la conoce allá («PORT-123»); puede faltar
     * @param number        el número dentro de su proyecto, si el origen lo tiene (para conservarlo)
     * @param type          el nombre del tipo en el origen («Story», «Historia de usuario», «Bug»…)
     * @param kind          lo que ese tipo es, según el adaptador
     * @param description   en Markdown
     * @param status        el nombre del estado en el origen
     * @param priority      el nombre de la prioridad en el origen; {@code null} si no tiene
     * @param parentId      la tarea de la que cuelga (su historia, su tarea o su épica)
     * @param moduleIds     los módulos del origen a los que pertenece (ver {@link ExtProject#modules})
     * @param points        story points o estimación: TaskFlow no los lleva, quedan anotados en la descripción
     * @param secondsSpent  tiempo registrado: lo mismo
     * @param extra         otros campos con valor que no tienen sitio en TaskFlow (nombre → valor), para anotarlos
     * @param commentCount  cuántos comentarios tiene, aunque {@code comments} venga vacío porque no se pidieron
     */
    public record ExtIssue(String id, String key, Integer number, String type, Kind kind, String title, String description,
                           String status, StatusCategory statusCategory, String priority, List<String> assigneeIds,
                           String creatorId, List<String> labels, String parentId, List<String> moduleIds,
                           List<String> sprintIds, LocalDate startDate, LocalDate dueDate, Instant createdAt,
                           Instant completedAt, Double points, Long secondsSpent, Map<String, String> extra,
                           List<ExtChecklistItem> checklist, List<ExtComment> comments, int commentCount,
                           List<ExtAttachment> attachments, List<ExtLink> links, String url, boolean archived) {
    }

    public record ExtChecklistItem(String text, boolean done) {
    }

    /**
     * @param authorName por si la persona no viene en {@link ImportBundle#users}
     * @param body       en Markdown
     */
    public record ExtComment(String id, String authorId, String authorName, String body, Instant createdAt) {
    }

    /**
     * @param url de dónde se descarga (con las credenciales del origen)
     */
    public record ExtAttachment(String id, String name, long size, String contentType, String url) {
    }

    /** Relación entre dos tareas, vista desde la que la lleva. */
    public enum LinkType {
        /** Esta bloquea a la otra. */
        BLOCKS,
        /** A esta la bloquea la otra. */
        BLOCKED_BY,
        RELATES,
        /** Esta duplica a la otra. */
        DUPLICATES
    }

    /**
     * @param targetId la otra tarea (su {@code id} en el origen)
     */
    public record ExtLink(LinkType type, String targetId) {
    }
}
