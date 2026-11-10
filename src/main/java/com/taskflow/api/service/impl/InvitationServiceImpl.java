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
import com.taskflow.api.ratelimit.RateLimitRule;
import com.taskflow.api.ratelimit.RateLimitService;
import com.taskflow.api.repository.WorkspaceInvitationRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.InvitationService;
import com.taskflow.api.util.TokenUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.taskflow.api.entity.CustomRole;
import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.repository.CustomRoleRepository;
import com.taskflow.api.security.AccessControl;
import com.taskflow.api.security.AccessControl.Access;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvitationServiceImpl implements InvitationService {

    static final String INVALID = "La invitación no es válida o ya expiró";

    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final ResourceLookup lookup;
    private final WorkspaceMapper workspaceMapper;
    private final EmailNotifier emailNotifier;
    private final AppProperties properties;
    private final RateLimitService rateLimitService;
    private final CustomRoleRepository customRoleRepository;
    private final AccessControl accessControl;

    @Override
    @Transactional
    public InvitationResponse invite(String slug, UUID inviterId, InviteMemberRequest request) {
        Workspace workspace = lookup.workspace(slug);
        // Con la fila del workspace bloqueada: dos invitaciones simultáneas al mismo correo van en fila y la segunda
        // reemplaza a la primera, así nunca quedan dos vigentes
        lookup.lockWorkspace(workspace);
        User inviter = lookup.user(inviterId);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (memberRepository.existsByWorkspaceIdAndUserEmail(workspace.getId(), email)) {
            throw new ConflictException("Esa persona ya es miembro del workspace");
        }
        // Cada invitación es un correo: límite por quien invita y por correo invitado (de cualquier workspace)
        rateLimitService.consume(RateLimitRule.INVITE_SENDER, inviterId.toString());
        rateLimitService.consume(RateLimitRule.INVITE_RECIPIENT, email);

        // Con un rol del workspace: debe existir y quien invita no puede dar un rol con permisos que el suyo no tiene.
        // Sin rol entra como «Miembro», el rol de entrada del workspace: eso no se restringe (quien puede invitar puede
        // sumar gente con el rol base, aunque el suyo tenga menos permisos), o «invitar» no serviría sin más permisos
        CustomRole customRole = null;
        if (request.roleId() != null) {
            customRole = customRoleRepository.findByIdAndWorkspaceId(request.roleId(), workspace.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Rol no encontrado"));
            Set<Permission> granted = customRole.permissionSet();
            boolean allowed = accessControl.find(slug, inviterId)
                    .map(access -> access.isManager() || access.covers(granted))
                    .orElse(false);
            if (!allowed) {
                throw new ForbiddenException("No puedes invitar con un rol que tiene permisos que tu rol no tiene");
            }
        }

        // Una sola invitación vigente por correo: la nueva reemplaza a las anteriores.
        invitationRepository.deleteNotAccepted(workspace.getId(), email);

        String rawToken = TokenUtils.generateToken();
        WorkspaceInvitation invitation = new WorkspaceInvitation();
        invitation.setWorkspace(workspace);
        invitation.setEmail(email);
        // Nunca entra como project manager (ese rol se da después, en Miembros): «Miembro» o el rol elegido
        invitation.setRole(WorkspaceRole.MEMBER);
        invitation.setCustomRole(customRole);
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

    /**
     * Con la invitación bloqueada: dos aceptaciones a la vez (doble clic, el enlace abierto en dos pestañas) van en fila.
     * Antes la segunda respondía 409 (las dos creaban el miembro) o, si llegaba después, 400 «no es válida o ya expiró»
     * aunque la persona ya había entrado. Ahora, mientras la invitación no venza, volver a aceptarla quien ya es miembro
     * gracias a ella responde el workspace otra vez.
     */
    @Override
    @Transactional
    public WorkspaceResponse accept(String token, UUID userId) {
        Instant now = Instant.now();
        WorkspaceInvitation invitation = invitationRepository.lockByTokenHash(TokenUtils.sha256(token))
                .filter(found -> found.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new BadRequestException(INVALID));
        User user = lookup.user(userId);
        boolean sameEmail = user.getEmail().equalsIgnoreCase(invitation.getEmail());
        Workspace workspace = invitation.getWorkspace();
        if (invitation.isAccepted()) {
            // Ya aceptada: solo quien la aceptó y sigue en el workspace la puede «volver a aceptar» (si salió o lo
            // quitaron, el enlace ya no lo devuelve)
            return memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                    .filter(member -> sameEmail)
                    .map(member -> workspaceMapper.toResponse(workspace, Access.of(member)))
                    .orElseThrow(() -> new BadRequestException(INVALID));
        }
        if (!sameEmail) {
            throw new ForbiddenException("Esta invitación fue enviada a otro correo");
        }

        WorkspaceMember member = memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .orElseGet(() -> {
                    WorkspaceMember created = new WorkspaceMember(workspace, user, invitation.getRole());
                    // El rol con el que se le invitó (si se eliminó mientras tanto, entra como «Miembro»)
                    created.setCustomRole(invitation.getCustomRole());
                    return memberRepository.save(created);
                });
        invitation.setAccepted(true);
        return workspaceMapper.toResponse(workspace, Access.of(member));
    }

    private WorkspaceInvitation findPending(String rawToken) {
        return invitationRepository.findByTokenHash(TokenUtils.sha256(rawToken))
                .filter(invitation -> invitation.isPending(Instant.now()))
                .orElseThrow(() -> new BadRequestException(INVALID));
    }
}
