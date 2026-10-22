package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 80, message = "El nombre no puede superar 80 caracteres")
        String name,

        @NotBlank(message = "El identificador es obligatorio")
        @Pattern(regexp = ValidationPatterns.PROJECT_IDENTIFIER,
                message = "El identificador debe empezar por letra y tener hasta 12 letras o números")
        String identifier,

        @Size(max = 10000, message = "La descripción es demasiado larga")
        String description
) {
}
