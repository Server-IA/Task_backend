package com.taskflow.api.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Un rol del workspace.
 *
 * @param key         identificador para la interfaz: {@code MANAGER}, {@code MEMBER} o el id del rol creado
 * @param id          id del rol creado por el workspace; {@code null} en los dos de fábrica
 * @param kind        {@code MANAGER} (project manager: todos los permisos, no se edita), {@code MEMBER} (el rol
 *                    «Miembro»: solo se editan sus permisos) o {@code CUSTOM} (creado por el workspace)
 * @param permissions permisos del rol (nombres de {@code Permission}), en el orden del catálogo
 * @param memberCount personas con este rol
 */
public record RoleResponse(
        String key,
        UUID id,
        Kind kind,
        String name,
        String description,
        List<String> permissions,
        long memberCount
) {

    public enum Kind {
        MANAGER, MEMBER, CUSTOM
    }
}
