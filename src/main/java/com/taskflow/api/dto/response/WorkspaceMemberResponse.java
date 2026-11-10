package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Un miembro del workspace.
 *
 * @param role     rol base: {@code OWNER} (project manager) o {@code MEMBER}
 * @param roleId   id del rol creado por el workspace; {@code null} con un rol de fábrica
 * @param roleName nombre del rol («Project manager», «Miembro», «Líder»…)
 */
public record WorkspaceMemberResponse(
        UserSummaryResponse user,
        WorkspaceRole role,
        UUID roleId,
        String roleName,
        Instant joinedAt
) {
}
