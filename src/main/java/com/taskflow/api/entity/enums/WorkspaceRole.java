package com.taskflow.api.entity.enums;

/**
 * Rol dentro de un workspace. No hay administradores: quien crea el workspace es el project manager
 * ({@code OWNER}) e invita al equipo ({@code MEMBER}). Puede haber varios project managers.
 */
public enum WorkspaceRole {
    /** Project manager: gestiona el workspace, sus miembros y todos los proyectos. */
    OWNER,
    /** Miembro del equipo: trabaja en las tareas de los proyectos a los que pertenece. */
    MEMBER;

    public boolean isManager() {
        return this == OWNER;
    }
}
