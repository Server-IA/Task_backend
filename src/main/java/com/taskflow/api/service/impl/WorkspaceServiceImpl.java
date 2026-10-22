package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.UpdateMemberRoleRequest;
import com.taskflow.api.dto.request.UpdateWorkspaceRequest;
import com.taskflow.api.dto.response.WorkspaceMemberResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.WorkspaceMapper;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import com.taskflow.api.service.WorkspaceService;
import com.taskflow.api.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final ResourceLookup lookup;
    private final WorkspaceMapper workspaceMapper;

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceResponse> getMyWorkspaces(UUID userId) {
        return memberRepository.findAllByUserIdWithWorkspace(userId).stream()
                .map(member -> workspaceMapper.toResponse(member.getWorkspace(), member.getRole()))
                .toList();
    }

    @Override
    @Transactional
    public WorkspaceResponse create(UUID userId, CreateWorkspaceRequest request) {
        User owner = lookup.user(userId);
        String slug = request.slug() != null ? request.slug() : generateUniqueSlug(request.name());
        if (workspaceRepository.existsBySlug(slug)) {
            throw new ConflictException("El slug '" + slug + "' ya está en uso");
        }

        Workspace workspace = new Workspace();
        workspace.setName(request.name().trim());
        workspace.setSlug(slug);
        workspace.setOwner(owner);
        workspaceRepository.save(workspace);
        memberRepository.save(new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER));
        return workspaceMapper.toResponse(workspace, WorkspaceRole.OWNER);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkspaceResponse get(String slug, UUID userId) {
        Workspace workspace = lookup.workspace(slug);
        return workspaceMapper.toResponse(workspace, roleOf(workspace, userId));
    }

    @Override
    @Transactional
    public WorkspaceResponse update(String slug, UUID userId, UpdateWorkspaceRequest request) {
        Workspace workspace = lookup.workspace(slug);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            workspace.setName(request.name().trim());
        }
        if (request.slug() != null && !request.slug().equals(workspace.getSlug())) {
            if (workspaceRepository.existsBySlug(request.slug())) {
                throw new ConflictException("El slug '" + request.slug() + "' ya está en uso");
            }
            workspace.setSlug(request.slug());
        }
        return workspaceMapper.toResponse(workspace, roleOf(workspace, userId));
    }

    @Override
    @Transactional
    public void delete(String slug) {
        workspaceRepository.delete(lookup.workspace(slug));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getMembers(String slug) {
        Workspace workspace = lookup.workspace(slug);
        return memberRepository.findAllByWorkspaceIdWithUser(workspace.getId()).stream()
                .map(workspaceMapper::toMemberResponse)
                .toList();
    }

    @Override
    @Transactional
    public WorkspaceMemberResponse updateMemberRole(String slug, UUID memberUserId, UpdateMemberRoleRequest request) {
        if (request.role() == WorkspaceRole.OWNER) {
            throw new BadRequestException("No se puede asignar el rol OWNER");
        }
        WorkspaceMember member = findMember(lookup.workspace(slug), memberUserId);
        if (member.getRole() == WorkspaceRole.OWNER) {
            throw new ForbiddenException("No se puede cambiar el rol del dueño del workspace");
        }
        member.setRole(request.role());
        return workspaceMapper.toMemberResponse(member);
    }

    @Override
    @Transactional
    public void removeMember(String slug, UUID actorId, UUID memberUserId) {
        Workspace workspace = lookup.workspace(slug);
        WorkspaceMember member = findMember(workspace, memberUserId);
        if (member.getRole() == WorkspaceRole.OWNER) {
            throw new ForbiddenException("El dueño no puede salir ni ser quitado del workspace");
        }
        if (!actorId.equals(memberUserId) && !roleOf(workspace, actorId).isAdmin()) {
            throw new ForbiddenException("Solo un administrador puede quitar miembros");
        }
        memberRepository.delete(member);
    }

    private WorkspaceRole roleOf(Workspace workspace, UUID userId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .map(WorkspaceMember::getRole)
                .orElseThrow(() -> new ForbiddenException("No eres miembro de este workspace"));
    }

    private WorkspaceMember findMember(Workspace workspace, UUID userId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("El usuario no es miembro del workspace"));
    }

    /** «Mi Equipo» → «mi-equipo», o «mi-equipo-2», «mi-equipo-3»… si ya existe. */
    private String generateUniqueSlug(String name) {
        String base = SlugUtils.slugify(name);
        if (base.length() < 3) {
            base = (base + "-workspace").replaceAll("^-", "");
        }
        String candidate = base;
        int suffix = 2;
        while (workspaceRepository.existsBySlug(candidate)) {
            String tail = "-" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 48 - tail.length())) + tail;
        }
        return candidate;
    }
}
