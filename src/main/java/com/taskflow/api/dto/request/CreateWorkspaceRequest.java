package com.taskflow.api.dto.request;

import com.taskflow.api.util.SlugUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Si no se envía el slug, se genera a partir del nombre.
 */
public record CreateWorkspaceRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 80, message = "El nombre no puede superar 80 caracteres")
        String name,

        @Size(min = 3, max = 48, message = "El slug debe tener entre 3 y 48 caracteres")
        @Pattern(regexp = SlugUtils.SLUG_PATTERN, message = "El slug solo admite minúsculas, números y guiones")
        String slug
) {
}
