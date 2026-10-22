package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Referencia corta a un issue (por ejemplo, el padre de un sub-issue).
 */
public record IssueSummaryResponse(
        UUID id,
        String code,
        String title
) {
}
