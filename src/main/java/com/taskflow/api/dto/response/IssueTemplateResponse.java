package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;

import java.util.List;
import java.util.UUID;

public record IssueTemplateResponse(
        UUID id,
        String name,
        /** Tipo de la tarea que crea (Historia de usuario → STORY, Reporte de bug → BUG, las demás → TASK). */
        IssueType type,
        /** Se antepone al título (por ejemplo «[Bug] »); {@code null} si no tiene. */
        String titlePrefix,
        /** Esqueleto en markdown de la descripción. */
        String description,
        /** {@code null}: la plantilla no fija prioridad. */
        IssuePriority priority,
        List<LabelResponse> labels,
        /** Estado con el que nace la tarea; {@code null}: la plantilla no lo fija. */
        StateResponse state,
        /** Asignados por defecto (miembros del proyecto), por nombre. */
        List<UserSummaryResponse> assignees,
        List<TemplateChecklistItemResponse> checklist,
        int position,
        /** Tareas creadas con la plantilla ({@code templateId} en {@code POST /issues}). */
        int usageCount
) {
}
