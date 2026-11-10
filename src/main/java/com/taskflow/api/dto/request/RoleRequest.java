package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Rol nuevo del workspace: su nombre y los permisos que tiene (nombres de {@code Permission}).
 */
public record RoleRequest(
        @NotBlank(message = "El nombre del rol es obligatorio")
        @Size(max = 40, message = "El nombre no puede superar 40 caracteres")
        String name,

        @Size(max = 200, message = "La descripción no puede superar 200 caracteres")
        String description,

        @NotNull(message = "Indica los permisos del rol (puede ser una lista vacía)")
        @Size(max = 100, message = "Demasiados permisos")
        List<@NotBlank(message = "Un permiso no puede estar vacío") String> permissions
) {
}
