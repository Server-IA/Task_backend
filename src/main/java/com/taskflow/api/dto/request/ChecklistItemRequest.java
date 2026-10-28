package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.ChecklistItemKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Criterio de aceptación ({@code CRITERION}) o paso de la checklist ({@code CHECK}). Se usa al agregar un elemento a
 * una tarea, al crear una tarea con su checklist y en las plantillas.
 */
public record ChecklistItemRequest(
        @NotNull(message = "El tipo es obligatorio (CRITERION o CHECK)")
        ChecklistItemKind kind,

        @NotBlank(message = "El texto es obligatorio")
        @Size(max = 500, message = "El texto no puede superar 500 caracteres")
        String text
) {
}
