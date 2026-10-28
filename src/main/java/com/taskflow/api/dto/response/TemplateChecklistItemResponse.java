package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ChecklistItemKind;

public record TemplateChecklistItemResponse(
        ChecklistItemKind kind,
        String text
) {
}
