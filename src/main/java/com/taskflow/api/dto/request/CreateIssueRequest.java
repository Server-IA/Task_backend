package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.IssuePriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Solo el título es obligatorio. Sin estado, el issue va al primer estado del tablero (Backlog);
 * sin prioridad, queda en NONE.
 */
public record CreateIssueRequest(
        @NotBlank(message = "El título es obligatorio")
        @Size(max = 255, message = "El título no puede superar 255 caracteres")
        String title,

        @Size(max = 50000, message = "La descripción es demasiado larga")
        String description,

        UUID stateId,

        IssuePriority priority,

        UUID parentId,

        LocalDate startDate,

        LocalDate dueDate,

        @Size(max = 20, message = "Máximo 20 asignados")
        List<UUID> assigneeIds,

        @Size(max = 20, message = "Máximo 20 etiquetas")
        List<UUID> labelIds
) {
}
