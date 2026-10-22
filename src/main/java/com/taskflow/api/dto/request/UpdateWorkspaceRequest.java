package com.taskflow.api.dto.request;

import com.taskflow.api.util.SlugUtils;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Los campos nulos no se modifican.
 */
public record UpdateWorkspaceRequest(
        @Size(min = 1, max = 80, message = "El nombre debe tener entre 1 y 80 caracteres")
        String name,

        @Size(min = 3, max = 48, message = "El slug debe tener entre 3 y 48 caracteres")
        @Pattern(regexp = SlugUtils.SLUG_PATTERN, message = "El slug solo admite minúsculas, números y guiones")
        String slug
) {
}
