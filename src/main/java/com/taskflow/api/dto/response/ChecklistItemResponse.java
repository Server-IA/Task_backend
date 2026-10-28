package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ChecklistItemKind;

import java.util.UUID;

public record ChecklistItemResponse(
        UUID id,
        ChecklistItemKind kind,
        String text,
        boolean done,
        /** Orden dentro de los elementos del mismo tipo (desde 0). */
        int position
) {
}
