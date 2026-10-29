package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.StateGroup;

import java.util.UUID;

public record StateResponse(
        UUID id,
        String name,
        String color,
        StateGroup group,
        int position
) {
}
