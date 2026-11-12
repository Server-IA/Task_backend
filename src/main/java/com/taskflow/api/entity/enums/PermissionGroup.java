package com.taskflow.api.entity.enums;

/**
 * Módulo de la aplicación al que pertenece un permiso (para mostrarlos agrupados y marcar un módulo completo).
 */
public enum PermissionGroup {
    ISSUES("Tareas"),
    STORIES("Historias"),
    COMMENTS("Comentarios"),
    CYCLES("Ciclos"),
    RETRO("Retrospectivas"),
    MODULES("Módulos"),
    DAILY("Daily"),
    INSIGHTS("Vistas, estadísticas y reportes"),
    PROJECTS("Proyectos"),
    WORKSPACE("Workspace y equipo"),
    INTEGRATIONS("Integraciones");

    private final String label;

    PermissionGroup(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
