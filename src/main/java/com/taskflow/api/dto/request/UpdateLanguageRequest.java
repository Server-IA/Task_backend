package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Idioma de la cuenta: {@code es} o {@code en}.
 */
public record UpdateLanguageRequest(
        @NotNull(message = "El idioma es obligatorio (es o en)")
        @Pattern(regexp = "es|en", message = "El idioma debe ser es o en")
        String language
) {
}
