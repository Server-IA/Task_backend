package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Edición parcial: los campos nulos no se modifican.
 * <ul>
 *   <li>Una descripción vacía la elimina.</li>
 *   <li>{@code assigneeIds} y {@code labelIds} reemplazan la lista completa ({@code []} la vacía).</li>
 *   <li>Para quitar el padre o las fechas se usan {@code clearParent}, {@code clearStartDate} y
 *       {@code clearDueDate}.</li>
 *   <li>{@code baseDescriptionVersion}: la {@code descriptionVersion} de la que partió quien cambia la descripción.
 *       Si la descripción cambió después (otra persona la guardó), responde 409 con la actual
 *       ({@code DescriptionConflictResponse}) y no cambia nada. Sin ella, gana el último que guarda.</li>
 *   <li>{@code type}: cambia el tipo. Una historia con tareas no puede dejar de serlo (409) y solo el project manager
 *       convierte en historia o deja de serlo.</li>
 *   <li>{@code reopenParent}: con {@code parentId} de una historia cerrada, la reabre para agregar esta tarea (solo el
 *       project manager); sin él, 409 con {@code code: STORY_CLOSED}.</li>
 * </ul>
 */
public record UpdateIssueRequest(
        @Size(min = 1, max = 255, message = "El título debe tener entre 1 y 255 caracteres")
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

        Boolean clearParent,

        Boolean clearStartDate,

        Boolean clearDueDate,

        @PositiveOrZero(message = "La versión de la descripción no es válida")
        Integer baseDescriptionVersion,

        IssueType type,

        Boolean reopenParent
) {
}
