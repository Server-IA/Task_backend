package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Crear una plantilla de tarea. Solo el nombre es obligatorio; queda al final de la lista.
 * {@code titlePrefix} se guarda tal cual (por ejemplo «[Bug] », con su espacio final) y una prioridad
 * {@code NONE} equivale a no fijarla. {@code stateId} debe ser un estado del proyecto y {@code assigneeIds},
 * miembros del proyecto. {@code type} es el tipo de la tarea que crea (sin él, {@code TASK}).
 */
public record IssueTemplateRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 60, message = "El nombre no puede superar 60 caracteres")
        String name,

        @Size(max = 40, message = "El prefijo no puede superar 40 caracteres")
        String titlePrefix,

        @Size(max = 50000, message = "La descripción es demasiado larga")
        String description,

        IssuePriority priority,

        @Size(max = 20, message = "Máximo 20 etiquetas")
        List<UUID> labelIds,

        @Size(max = 50, message = "Máximo 50 elementos en la checklist")
        List<@Valid ChecklistItemRequest> checklist,

        /** Estado con el que nace la tarea; {@code null}: la plantilla no lo fija. */
        UUID stateId,

        /** Asignados por defecto. */
        @Size(max = 20, message = "Máximo 20 asignados")
        List<UUID> assigneeIds,

        /** Tipo de la tarea que crea; {@code null}: {@code TASK}. */
        IssueType type
) {
}
