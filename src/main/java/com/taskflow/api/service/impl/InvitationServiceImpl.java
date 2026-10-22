package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.InviteMemberRequest;
import com.taskflow.api.dto.response.InvitationDetailsResponse;
import com.taskflow.api.dto.response.InvitationResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;
import com.taskflow.api.email.EmailNotifier;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceInvitation;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.WorkspaceMapper;
import com.taskflow.api.repository.WorkspaceInvitationRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.InvitationService;
import com.taskflow.api.util.TokenUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvitationServiceImpl implements InvitationService {

    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final ResourceLookup lookup;
    private final WorkspaceMapper workspaceMapper;
    private final EmailNotifier emailNotifier;
    private final AppProperties properties;

    @Override
    @Transactional
    public InvitationResponse invite(String slug, UUID inviterId, InviteMemberRequest request) {
        if (request.role() == WorkspaceRole.OWNER) {
            throw new BadRequestException("Solo se puede invitar con rol ADMIN o MEMBER");
        }
        Workspace workspace = lookup.workspace(slug);
        User inviter = lookup.user(inviterId);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (memberRepository.existsByWorkspaceIdAndUserEmail(workspace.getId(), email)) {
            throw new ConflictException("Esa persona ya es miembro del workspace");
        }

        // Una sola invitación vigente por correo: la nueva reemplaza a las anteriores.
        invitationRepository.deleteNotAccepted(workspace.getId(), email);

        String rawToken = TokenUtils.generateToken();
        WorkspaceInvitation invitation = new WorkspaceInvitation();
        invitation.setWorkspace(workspace);
        invitation.setEmail(email);
        invitation.setRole(request.role());
        invitation.setInvitedBy(inviter);
        invitation.setTokenHash(TokenUtils.sha256(rawToken));
        invitation.setExpiresAt(Instant.now().plus(Duration.ofDays(properties.tokens().invitationDays())));
        invitationRepository.save(invitation);

        emailNotifier.sendWorkspaceInvitation(email, inviter.getFullName(), workspace.getName(), rawToken);
        return workspaceMapper.toInvitationResponse(invitation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvitationResponse> getPending(String slug) {
        Workspace workspace = lookup.workspace(slug);
        return invitationRepository.findPending(workspace.getId(), Instant.now()).stream()
                .map(workspaceMapper::toInvitationResponse)
                .toList();
    }

    @Override
    @Transactional
    public void revoke(String slug, UUID invitationId) {
        Workspace workspace = lookup.workspace(slug);
        WorkspaceInvitation invitation = invitationRepository.findByIdAndWorkspaceId(invitationId, workspace.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Invitación no encontrada"));
        if (invitation.isAccepted()) {
            throw new BadRequestException("La invitación ya fue aceptada");
        }
        invitationRepository.delete(invitation);
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationDetailsResponse getDetails(String token) {
        return workspaceMapper.toInvitationDetails(findPending(token));
    }

    @Override
    @Transactional
    public WorkspaceResponse accept(String token, UUID userId) {
        WorkspaceInvitation invitation = findPending(token);
        User user = lookup.user(userId);
        if (!user.getEmail().equalsIgnoreCase(invitation.getEmail())) {
            throw new ForbiddenException("Esta invitación fue enviada a otro correo");
        }

        Workspace workspace = invitation.getWorkspace();
        WorkspaceRole role = memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .map(WorkspaceMember::getRole)
                .orElseGet(() -> {
                    memberRepository.save(new WorkspaceMember(workspace, user, invitation.getRole()));
                    return invitation.getRole();
                });
        invitation.setAccepted(true);
        return workspaceMapper.toResponse(workspace, role);
    }

    private WorkspaceInvitation findPending(String rawToken) {
        return invitationRepository.findByTokenHash(TokenUtils.sha256(rawToken))
                .filter(invitation -> invitation.isPending(Instant.now()))
                .orElseThrow(() -> new BadRequestException("La invitación no es válida o ya expiró"));
    }
}
