package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Edición parcial de una plantilla: los campos nulos no se modifican.
 * <ul>
 *   <li>{@code titlePrefix: ""} y {@code description: ""} los quitan; {@code priority: "NONE"} quita la prioridad.</li>
 *   <li>{@code labelIds}, {@code assigneeIds} y {@code checklist} reemplazan la lista completa ({@code []} la
 *       vacía).</li>
 *   <li>{@code stateId} cambia el estado por defecto y {@code clearState: true} lo quita.</li>
 *   <li>{@code position} es el nuevo índice (desde 0) entre las plantillas del proyecto.</li>
 *   <li>{@code type} cambia el tipo de la tarea que crea.</li>
 * </ul>
 */
public record UpdateIssueTemplateRequest(
        @Size(min = 1, max = 60, message = "El nombre debe tener entre 1 y 60 caracteres")
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

        @Min(value = 0, message = "La posición no puede ser negativa")
        Integer position,

        UUID stateId,

        Boolean clearState,

        @Size(max = 20, message = "Máximo 20 asignados")
        List<UUID> assigneeIds,

        IssueType type
) {
}
