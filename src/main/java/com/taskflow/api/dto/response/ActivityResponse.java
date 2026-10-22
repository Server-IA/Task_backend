package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ActivityField;

import java.time.Instant;
import java.util.UUID;

public record ActivityResponse(
        UUID id,
        UserSummaryResponse actor,
        ActivityField field,
        String oldValue,
        String newValue,
        Instant createdAt
) {
}
