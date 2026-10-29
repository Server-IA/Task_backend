package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Una línea de «Partir en tareas». {@code type}: {@code TASK} (por defecto) o {@code BUG}; sin {@code priority}, la de
 * la historia. Ciclo, módulos y etiquetas los hereda de la historia.
 */
public record SplitItemRequest(
        @NotBlank(message = "El título es obligatorio")
        @Size(max = 255, message = "El título no puede superar 255 caracteres")
        String title,

        IssueType type,

        IssuePriority priority,

        @Size(max = 20, message = "Máximo 20 asignados")
        List<UUID> assigneeIds
) {
}
