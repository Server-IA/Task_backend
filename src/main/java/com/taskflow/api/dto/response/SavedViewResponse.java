package com.taskflow.api.dto.response;

import com.taskflow.api.entity.SavedViewFilters;

import java.util.UUID;

public record SavedViewResponse(
        UUID id,
        String name,
        boolean shared,
        /** Quien la creó. */
        UserSummaryResponse owner,
        SavedViewFilters filters,
        /** Orden dentro de su grupo (compartidas o personales), desde 0. */
        int position,
        /** {@code true} si la creó el usuario actual. */
        boolean mine
) {
}
