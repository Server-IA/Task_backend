package com.taskflow.api.service;

import com.taskflow.api.dto.request.InviteMemberRequest;
import com.taskflow.api.dto.response.InvitationDetailsResponse;
import com.taskflow.api.dto.response.InvitationResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;

import java.util.List;
import java.util.UUID;

public interface InvitationService {

    InvitationResponse invite(String slug, UUID inviterId, InviteMemberRequest request);

    List<InvitationResponse> getPending(String slug);

    void revoke(String slug, UUID invitationId);

    InvitationDetailsResponse getDetails(String token);

    WorkspaceResponse accept(String token, UUID userId);
}
