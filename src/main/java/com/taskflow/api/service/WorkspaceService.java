package com.taskflow.api.service;

import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.UpdateMemberRoleRequest;
import com.taskflow.api.dto.request.UpdateWorkspaceRequest;
import com.taskflow.api.dto.response.WorkspaceMemberResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;

import java.util.List;
import java.util.UUID;

public interface WorkspaceService {

    List<WorkspaceResponse> getMyWorkspaces(UUID userId);

    WorkspaceResponse create(UUID userId, CreateWorkspaceRequest request);

    WorkspaceResponse get(String slug, UUID userId);

    WorkspaceResponse update(String slug, UUID userId, UpdateWorkspaceRequest request);

    void delete(String slug);

    List<WorkspaceMemberResponse> getMembers(String slug);

    /** Cambia entre project manager (OWNER) y MEMBER; nunca deja el workspace sin project manager (409). */
    WorkspaceMemberResponse updateMemberRole(String slug, UUID memberUserId, UpdateMemberRoleRequest request);

    /**
     * El project manager puede quitar a otros miembros y cualquier miembro puede salirse, salvo el último
     * project manager (409).
     */
    void removeMember(String slug, UUID actorId, UUID memberUserId);
}
