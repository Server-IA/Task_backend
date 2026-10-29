package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.IssueType;

import java.util.UUID;

/**
 * Referencia corta a un issue (por ejemplo, el padre de un sub-issue).
 */
public record IssueSummaryResponse(
        UUID id,
        String code,
        String title,
        /** Tipo (para el icono y el chip «↰ WEB-15» de la historia). */
        IssueType type
) {

    /** Una tarea ({@code TASK}). */
    public IssueSummaryResponse(UUID id, String code, String title) {
        this(id, code, title, IssueType.TASK);
    }
}
