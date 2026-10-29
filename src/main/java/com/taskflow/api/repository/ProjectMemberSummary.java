package com.taskflow.api.repository;

import com.taskflow.api.dto.response.UserSummaryResponse;

import java.util.List;

/**
 * Resumen de los miembros de un proyecto para su tarjeta: cuántos son y los primeros
 * ({@link ProjectMemberRepository#MEMBER_PREVIEW}, por nombre) para mostrar sus avatares.
 */
public record ProjectMemberSummary(int memberCount, List<UserSummaryResponse> members) {

    public static ProjectMemberSummary empty() {
        return new ProjectMemberSummary(0, List.of());
    }
}
