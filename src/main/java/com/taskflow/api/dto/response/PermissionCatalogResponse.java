package com.taskflow.api.dto.response;

import java.util.List;

/**
 * Catálogo de permisos, agrupados por módulo de la aplicación, para armar la pantalla de roles.
 */
public record PermissionCatalogResponse(List<Group> groups) {

    /**
     * @param key   nombre del grupo ({@code PermissionGroup})
     * @param label cómo se muestra («Tareas», «Ciclos»…)
     */
    public record Group(String key, String label, List<Item> permissions) {
    }

    /**
     * @param key           nombre del permiso ({@code Permission}): lo que se guarda en un rol
     * @param memberDefault si lo trae el rol «Miembro» de un workspace nuevo
     */
    public record Item(String key, String label, String description, boolean memberDefault) {
    }
}
