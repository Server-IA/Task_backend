package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.ProjectColor;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Los campos nulos no se modifican. Una descripción vacía la elimina.
 */
public record UpdateProjectRequest(
        @Size(min = 1, max = 80, message = "El nombre debe tener entre 1 y 80 caracteres")
        String name,

        @Pattern(regexp = ValidationPatterns.PROJECT_IDENTIFIER,
                message = "El identificador debe empezar por letra y tener hasta 12 letras o números")
        String identifier,

        @Size(max = 10000, message = "La descripción es demasiado larga")
        String description,

        ProjectColor color
) {
}
