package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Cambios a un rol creado por el workspace: lo que venga {@code null} no cambia. Una descripción vacía la borra.
 */
public record UpdateRoleRequest(
        @Size(max = 40, message = "El nombre no puede superar 40 caracteres")
        String name,

        @Size(max = 200, message = "La descripción no puede superar 200 caracteres")
        String description,

        @Size(max = 100, message = "Demasiados permisos")
        List<@NotBlank(message = "Un permiso no puede estar vacío") String> permissions
) {
}
