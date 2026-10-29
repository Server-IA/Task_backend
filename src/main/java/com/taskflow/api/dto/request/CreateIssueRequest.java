package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Solo el título es obligatorio. Sin estado, el issue va al primer estado del tablero (Backlog);
 * sin prioridad, queda en NONE. {@code checklist} crea sus criterios y pasos en la misma transacción.
 * {@code templateId} es la plantilla con la que el frontend rellenó el formulario (solo cuenta su uso).
 * <ul>
 *   <li>{@code type}: historia, tarea o bug (sin él, {@code TASK}). Solo el project manager crea historias.</li>
 *   <li>{@code reopenParent}: el padre es una historia cerrada y la tarea nueva es una {@code TASK}: la reabre (solo
 *       el project manager). Sin él, 409 con {@code code: STORY_CLOSED}. Un bug se liga sin reabrirla.</li>
 *   <li>{@code relatesToId}: la tarea nueva queda relacionada ({@code RELATES}) con esa, en la misma transacción
 *       (la «tarea suelta relacionada» con una historia cerrada, o una historia nueva relacionada con otra).</li>
 * </ul>
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
        List<UUID> labelIds,

        /** Criterios y pasos que se crean junto con la tarea (por ejemplo, los de una plantilla), en orden. */
        @Size(max = 50, message = "Máximo 50 elementos en la checklist")
        List<@Valid ChecklistItemRequest> checklist,

        /** Plantilla usada (del mismo proyecto); suma un uso a la plantilla. */
        UUID templateId,

        IssueType type,

        Boolean reopenParent,

        UUID relatesToId
) {
}
