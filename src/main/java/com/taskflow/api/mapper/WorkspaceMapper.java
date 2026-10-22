package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.InvitationDetailsResponse;
import com.taskflow.api.dto.response.InvitationResponse;
import com.taskflow.api.dto.response.WorkspaceMemberResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceInvitation;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.WorkspaceRole;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(uses = UserMapper.class)
public interface WorkspaceMapper {

    @Mapping(target = "id", source = "workspace.id")
    @Mapping(target = "name", source = "workspace.name")
    @Mapping(target = "slug", source = "workspace.slug")
    @Mapping(target = "owner", source = "workspace.owner")
    @Mapping(target = "createdAt", source = "workspace.createdAt")
    @Mapping(target = "myRole", source = "myRole")
    WorkspaceResponse toResponse(Workspace workspace, WorkspaceRole myRole);

    @Mapping(target = "joinedAt", source = "createdAt")
    WorkspaceMemberResponse toMemberResponse(WorkspaceMember member);

    InvitationResponse toInvitationResponse(WorkspaceInvitation invitation);

    @Mapping(target = "workspaceName", source = "workspace.name")
    @Mapping(target = "workspaceSlug", source = "workspace.slug")
    @Mapping(target = "invitedByName", source = "invitedBy.fullName")
    InvitationDetailsResponse toInvitationDetails(WorkspaceInvitation invitation);
}
