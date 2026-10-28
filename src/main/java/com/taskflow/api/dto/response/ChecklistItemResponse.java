package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ChecklistItemKind;

import java.time.Instant;
import java.util.UUID;

/**
 * @param position  orden dentro de los elementos del mismo tipo (desde 0)
 * @param checkedBy quien lo marcó como hecho; {@code null} si no está marcado o si se marcó antes de que se guardara
 *                  este dato
 * @param checkedAt cuándo se marcó como hecho; {@code null} si no está marcado
 */
public record ChecklistItemResponse(
        UUID id,
        ChecklistItemKind kind,
        String text,
        boolean done,
        int position,
        UserSummaryResponse checkedBy,
        Instant checkedAt
) {
}
