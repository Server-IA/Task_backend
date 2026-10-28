package com.taskflow.api.dto.response;

import java.time.Instant;

/**
 * Nota de la daily de un día.
 *
 * @param updatedBy quien la escribió o editó por última vez; {@code null} si su cuenta se eliminó
 */
public record DailyNoteResponse(
        String text,
        UserSummaryResponse updatedBy,
        Instant updatedAt
) {
}
