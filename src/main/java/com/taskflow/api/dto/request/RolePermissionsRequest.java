package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Permisos del rol «Miembro» del workspace (nombres de {@code Permission}): reemplazan a los que tenía.
 */
public record RolePermissionsRequest(
        @NotNull(message = "Indica los permisos del rol (puede ser una lista vacía)")
        @Size(max = 100, message = "Demasiados permisos")
        List<@NotBlank(message = "Un permiso no puede estar vacío") String> permissions
) {
}
