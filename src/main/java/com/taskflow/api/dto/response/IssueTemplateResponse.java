package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.IssuePriority;

import java.util.List;
import java.util.UUID;

public record IssueTemplateResponse(
        UUID id,
        String name,
        /** Se antepone al título (por ejemplo «[Bug] »); {@code null} si no tiene. */
        String titlePrefix,
        /** Esqueleto en markdown de la descripción. */
        String description,
        /** {@code null}: la plantilla no fija prioridad. */
        IssuePriority priority,
        List<LabelResponse> labels,
        List<TemplateChecklistItemResponse> checklist,
        int position
) {
}
