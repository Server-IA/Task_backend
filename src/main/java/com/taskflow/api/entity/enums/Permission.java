package com.taskflow.api.entity.enums;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Lo que un rol puede hacer en un workspace. El project manager los tiene todos; el rol «Miembro» y los roles que
 * crea cada workspace llevan la lista que se les marque. Los nombres se guardan en la base de datos (JSON) y viajan
 * al frontend: no se renombran.
 * <p>
 * Ver un proyecto no es un permiso: lo da ser miembro del proyecto (o {@link #PROJECT_VIEW_ALL}). Lo que cada quien
 * hace con lo suyo tampoco: editar o borrar su propio comentario, sus notas de retro o sus adjuntos, sus vistas
 * personales, su perfil y sus avisos. Eliminar el workspace es solo del project manager.
 */
public enum Permission {

    // ----- Tareas
    ISSUE_CREATE(PermissionGroup.ISSUES, "Crear tareas y bugs", "Crear tareas, bugs y subtareas."),
    ISSUE_EDIT(PermissionGroup.ISSUES, "Editar tareas",
            "Cambiar título, descripción, estado, prioridad, asignados, etiquetas y fechas, y mover tarjetas en el tablero."),
    ISSUE_DELETE_OWN(PermissionGroup.ISSUES, "Eliminar sus propias tareas", "Borrar las tareas y bugs que creó."),
    ISSUE_DELETE_ANY(PermissionGroup.ISSUES, "Eliminar cualquier tarea", "Borrar tareas y bugs creados por otras personas."),
    ISSUE_CHECKLIST(PermissionGroup.ISSUES, "Checklist y criterios",
            "Agregar, marcar, editar y quitar elementos del checklist y los criterios de aceptación."),
    ISSUE_RELATIONS(PermissionGroup.ISSUES, "Dependencias", "Agregar y quitar bloqueos y relaciones entre tareas."),
    ISSUE_ATTACH(PermissionGroup.ISSUES, "Subir adjuntos", "Adjuntar archivos a las tareas."),
    FILE_DELETE_ANY(PermissionGroup.ISSUES, "Eliminar adjuntos de otros", "Borrar archivos que subió otra persona."),

    // ----- Historias
    STORY_MANAGE(PermissionGroup.STORIES, "Crear y gestionar historias",
            "Crear historias, convertir una tarea en historia (o al revés), partirlas en tareas y eliminarlas."),
    STORY_CLOSE(PermissionGroup.STORIES, "Cerrar y reabrir historias",
            "Cerrar una historia, reabrirla y reabrir el trabajo de una historia cerrada."),

    // ----- Comentarios
    COMMENT_CREATE(PermissionGroup.COMMENTS, "Comentar", "Escribir comentarios en las tareas."),
    COMMENT_IMAGES(PermissionGroup.COMMENTS, "Subir imágenes en comentarios", "Pegar o adjuntar imágenes dentro de un comentario."),
    COMMENT_DELETE_ANY(PermissionGroup.COMMENTS, "Eliminar comentarios de otros", "Borrar comentarios escritos por otras personas."),

    // ----- Ciclos
    CYCLE_CREATE(PermissionGroup.CYCLES, "Crear ciclos", "Crear ciclos (sprints)."),
    CYCLE_EDIT(PermissionGroup.CYCLES, "Editar ciclos", "Cambiar nombre, descripción y fechas de un ciclo."),
    CYCLE_DELETE(PermissionGroup.CYCLES, "Eliminar ciclos", "Borrar ciclos (las tareas no se borran)."),
    CYCLE_CLOSE(PermissionGroup.CYCLES, "Cerrar ciclos", "Cerrar un ciclo y decidir qué pasa con lo que quedó abierto."),
    CYCLE_ASSIGN(PermissionGroup.CYCLES, "Mover tareas entre ciclos", "Agregar tareas a un ciclo y quitarlas."),

    // ----- Retrospectivas
    RETRO_MANAGE(PermissionGroup.RETRO, "Dirigir la retrospectiva",
            "Iniciarla, terminarla, editar o quitar notas de otras personas y convertir acciones en tareas (esto último "
                    + "pide además crear tareas)."),
    RETRO_PARTICIPATE(PermissionGroup.RETRO, "Participar en la retrospectiva", "Escribir notas y votar."),

    // ----- Módulos
    MODULE_CREATE(PermissionGroup.MODULES, "Crear módulos", "Crear módulos."),
    MODULE_EDIT(PermissionGroup.MODULES, "Editar módulos", "Cambiar nombre, descripción y fechas de un módulo."),
    MODULE_DELETE(PermissionGroup.MODULES, "Eliminar módulos", "Borrar módulos (las tareas no se borran)."),
    MODULE_ASSIGN(PermissionGroup.MODULES, "Mover tareas entre módulos", "Agregar tareas a un módulo y quitarlas."),

    // ----- Daily
    DAILY_NOTE(PermissionGroup.DAILY, "Escribir la nota del día", "Editar las notas de la daily."),
    DAILY_SETTINGS(PermissionGroup.DAILY, "Configurar la daily",
            "Elegir los bloques que se exponen y los puntos a tratar."),

    // ----- Vistas, estadísticas y reportes
    VIEW_SHARED(PermissionGroup.INSIGHTS, "Vistas compartidas",
            "Crear, editar, ordenar y eliminar las vistas guardadas que ve todo el proyecto."),
    STATS_VIEW(PermissionGroup.INSIGHTS, "Ver estadísticas", "Abrir las estadísticas del proyecto."),
    REPORT_GENERATE(PermissionGroup.INSIGHTS, "Generar reportes", "Sacar reportes en PDF."),
    ACTIVITY_VIEW(PermissionGroup.INSIGHTS, "Ver la actividad", "Abrir la actividad del workspace (de los proyectos que ve)."),

    // ----- Proyectos
    PROJECT_CREATE(PermissionGroup.PROJECTS, "Crear proyectos", "Crear proyectos en el workspace."),
    PROJECT_EDIT(PermissionGroup.PROJECTS, "Editar proyectos", "Cambiar nombre, identificador, color y descripción."),
    PROJECT_DELETE(PermissionGroup.PROJECTS, "Eliminar proyectos", "Borrar un proyecto con todo su contenido."),
    PROJECT_MEMBERS(PermissionGroup.PROJECTS, "Miembros del proyecto", "Agregar y quitar personas de un proyecto."),
    PROJECT_STATES(PermissionGroup.PROJECTS, "Estados", "Crear, editar, ordenar y eliminar los estados del tablero."),
    PROJECT_LABELS(PermissionGroup.PROJECTS, "Etiquetas", "Crear, editar y eliminar etiquetas."),
    PROJECT_TEMPLATES(PermissionGroup.PROJECTS, "Plantillas", "Crear, editar y eliminar plantillas de tareas."),
    PROJECT_VIEW_ALL(PermissionGroup.PROJECTS, "Ver todos los proyectos",
            "Entrar a todos los proyectos del workspace sin ser miembro de cada uno."),

    // ----- Workspace
    WORKSPACE_EDIT(PermissionGroup.WORKSPACE, "Editar el workspace", "Cambiar el nombre y la URL del workspace."),
    MEMBER_INVITE(PermissionGroup.WORKSPACE, "Invitar miembros", "Enviar, ver y cancelar invitaciones."),
    MEMBER_REMOVE(PermissionGroup.WORKSPACE, "Quitar miembros", "Sacar personas del workspace."),
    MEMBER_ROLE(PermissionGroup.WORKSPACE, "Cambiar roles de miembros", "Asignar a cada persona uno de los roles del workspace."),
    ROLE_MANAGE(PermissionGroup.WORKSPACE, "Gestionar roles", "Crear, editar y eliminar roles y sus permisos."),
    STORAGE_VIEW(PermissionGroup.WORKSPACE, "Ver el almacenamiento", "Ver el detalle del espacio que ocupan los archivos."),
    DATA_IMPORT(PermissionGroup.WORKSPACE, "Importar datos",
            "Traer proyectos y tareas desde Jira, Trello, Linear, Asana o un archivo, y deshacer una importación.");

    /** Todos: lo que tiene el project manager. */
    public static final Set<Permission> ALL = Collections.unmodifiableSet(EnumSet.allOf(Permission.class));

    /**
     * Lo que trae el rol «Miembro» al crear un workspace: trabajar las tareas de sus proyectos, sin configurar nada.
     * Es lo mismo que podía hacer un miembro antes de que existieran los permisos (y el valor por defecto de la
     * columna {@code workspaces.member_permissions}).
     */
    public static final Set<Permission> MEMBER_DEFAULTS = Collections.unmodifiableSet(EnumSet.of(
            ISSUE_CREATE, ISSUE_EDIT, ISSUE_DELETE_OWN, ISSUE_CHECKLIST, ISSUE_RELATIONS, ISSUE_ATTACH,
            COMMENT_CREATE, COMMENT_IMAGES,
            CYCLE_ASSIGN, MODULE_ASSIGN,
            RETRO_PARTICIPATE,
            DAILY_NOTE,
            STATS_VIEW, REPORT_GENERATE, ACTIVITY_VIEW));

    private final PermissionGroup group;
    private final String label;
    private final String description;

    Permission(PermissionGroup group, String label, String description) {
        this.group = group;
        this.label = label;
        this.description = description;
    }

    public PermissionGroup getGroup() {
        return group;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Los permisos de una lista de nombres guardada: los que ya no existen (o están mal escritos) se ignoran, así un
     * permiso retirado no rompe los roles que lo tenían.
     */
    public static Set<Permission> fromNames(Collection<String> names) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        if (names != null) {
            for (String name : names) {
                for (Permission permission : values()) {
                    if (permission.name().equals(name)) {
                        result.add(permission);
                    }
                }
            }
        }
        return result;
    }

    /** Los nombres, en el orden del catálogo (para guardarlos y devolverlos siempre igual). */
    public static java.util.List<String> names(Collection<Permission> permissions) {
        return java.util.Arrays.stream(values())
                .filter(permissions::contains)
                .map(Enum::name)
                .toList();
    }
}
