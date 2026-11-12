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
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueTemplateRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import com.taskflow.api.service.WorkspaceService;
import com.taskflow.api.util.HumanNames;
import com.taskflow.api.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.taskflow.api.entity.CustomRole;
import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.repository.CustomRoleRepository;
import com.taskflow.api.security.AccessControl.Access;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    /** Workspaces que puede crear (y tener como dueño) una persona. */
    static final int MAX_OWNED_WORKSPACES = 10;
    static final String WORKSPACE_LIMIT_MESSAGE =
            "Ya tienes " + MAX_OWNED_WORKSPACES + " workspaces: elimina uno o pide que te inviten a otro";

    static final String LAST_MANAGER_MESSAGE =
            "El workspace debe tener al menos un project manager: asigna ese rol a otra persona primero";

    static final String REMOVE_FORBIDDEN = "Tu rol no permite quitar miembros";
    static final String MANAGER_ROLE_FORBIDDEN = "Solo un project manager puede dar, quitar o sacar a un project manager";

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final IssueTemplateRepository templateRepository;
    private final IssueRepository issueRepository;
    private final CustomRoleRepository customRoleRepository;
    private final ResourceLookup lookup;
    private final WorkspaceMapper workspaceMapper;

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceResponse> getMyWorkspaces(UUID userId) {
        return memberRepository.findAllByUserIdWithWorkspace(userId).stream()
                .map(member -> workspaceMapper.toResponse(member.getWorkspace(), Access.of(member)))
                .toList();
    }

    @Override
    @Transactional
    public WorkspaceResponse create(UUID userId, CreateWorkspaceRequest request) {
        // Con la fila del usuario bloqueada: dos creaciones a la vez se cuentan en fila y no pasan juntas del máximo
        lookup.lockUser(userId);
        User owner = lookup.user(userId);
        if (workspaceRepository.countByOwnerId(userId) >= MAX_OWNED_WORKSPACES) {
            throw new ConflictException(WORKSPACE_LIMIT_MESSAGE, ConflictException.WORKSPACE_LIMIT);
        }
        String name = HumanNames.required(request.name(), "name");
        String slug = request.slug() != null ? request.slug() : generateUniqueSlug(name);
        ensureNotReserved(slug);
        if (workspaceRepository.existsBySlug(slug)) {
            throw new ConflictException("El slug '" + slug + "' ya está en uso");
        }

        Workspace workspace = new Workspace();
        workspace.setName(name);
        workspace.setSlug(slug);
        workspace.setOwner(owner);
        workspaceRepository.save(workspace);
        // Quien crea el workspace empieza como project manager; después crea los roles que necesite
        memberRepository.save(new WorkspaceMember(workspace, owner, WorkspaceRole.OWNER));
        return workspaceMapper.toResponse(workspace, Access.manager());
    }

    @Override
    @Transactional(readOnly = true)
    public WorkspaceResponse get(String slug, UUID userId) {
        Workspace workspace = lookup.workspace(slug);
        return workspaceMapper.toResponse(workspace, accessOf(workspace, userId));
    }

    @Override
    @Transactional
    public WorkspaceResponse update(String slug, UUID userId, UpdateWorkspaceRequest request) {
        Workspace workspace = lookup.workspace(slug);
        if (request.name() != null) {
            workspace.setName(HumanNames.required(request.name(), "name"));
        }
        if (request.slug() != null && !request.slug().equals(workspace.getSlug())) {
            ensureNotReserved(request.slug());
            if (workspaceRepository.existsBySlug(request.slug())) {
                throw new ConflictException("El slug '" + request.slug() + "' ya está en uso");
            }
            workspace.setSlug(request.slug());
        }
        return workspaceMapper.toResponse(workspace, accessOf(workspace, userId));
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
    public WorkspaceMemberResponse updateMemberRole(String slug, UUID actorId, UUID memberUserId,
                                                    UpdateMemberRoleRequest request) {
        Workspace workspace = lockWorkspace(slug);
        Access actor = accessOf(workspace, actorId);
        WorkspaceMember member = findMember(workspace, memberUserId);
        WorkspaceRole role = request.role();
        CustomRole custom = null;
        if (request.roleId() != null) {
            if (role.isManager()) {
                throw new BadRequestException("El project manager no lleva otro rol: envía solo role");
            }
            custom = customRoleRepository.findByIdAndWorkspaceId(request.roleId(), workspace.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Rol no encontrado"));
        }
        if (member.getRole() == role && Objects.equals(idOf(member.getCustomRole()), idOf(custom))) {
            return workspaceMapper.toMemberResponse(member);
        }
        ensureCanAssign(actor, workspace, member, role, custom);
        if (member.getRole().isManager()) {
            handOverIfLastManager(workspace, memberUserId);
        }
        member.setRole(role);
        member.setCustomRole(custom);
        return workspaceMapper.toMemberResponse(member);
    }

    /**
     * Quien no es project manager no puede usar el cambio de rol para darse (o dar) más de lo que tiene: no toca el
     * rol de project manager, no asigna un rol con permisos que el suyo no tiene y no le cambia el rol a alguien que
     * tiene más permisos.
     */
    private static void ensureCanAssign(Access actor, Workspace workspace, WorkspaceMember member, WorkspaceRole role,
                                        CustomRole custom) {
        if (actor.isManager()) {
            return;
        }
        if (role.isManager() || member.getRole().isManager()) {
            throw new ForbiddenException(MANAGER_ROLE_FORBIDDEN);
        }
        Set<Permission> target = custom != null ? custom.permissionSet() : workspace.memberPermissionSet();
        if (!actor.covers(target)) {
            throw new ForbiddenException("No puedes asignar un rol con permisos que tu rol no tiene");
        }
        if (!actor.covers(Access.of(member).permissions())) {
            throw new ForbiddenException("No puedes cambiar el rol de alguien con más permisos que tú");
        }
    }

    private static UUID idOf(CustomRole role) {
        return role == null ? null : role.getId();
    }

    @Override
    @Transactional
    public void removeMember(String slug, UUID actorId, UUID memberUserId) {
        Workspace workspace = lockWorkspace(slug);
        boolean leaving = actorId.equals(memberUserId);
        Access actor = leaving ? null : accessOf(workspace, actorId);
        if (actor != null && !actor.can(Permission.MEMBER_REMOVE)) {
            throw new ForbiddenException(REMOVE_FORBIDDEN);
        }
        WorkspaceMember member = findMember(workspace, memberUserId);
        if (actor != null && member.getRole().isManager() && !actor.isManager()) {
            throw new ForbiddenException(MANAGER_ROLE_FORBIDDEN);
        }
        if (member.getRole().isManager()) {
            handOverIfLastManager(workspace, memberUserId);
        }
        templateRepository.removeAssigneeInWorkspace(workspace.getId(), memberUserId);
        projectMemberRepository.deleteAllInWorkspace(workspace.getId(), memberUserId);
        // Sin acceso al workspace, deja de estar asignado a sus tareas (y de recibir sus avisos)
        issueRepository.removeAssigneeInWorkspace(workspace.getId(), memberUserId);
        memberRepository.delete(member);
    }

    /**
     * Antes de que un project manager deje de serlo (o salga), comprueba que quede otro: el workspace nunca
     * se queda sin project manager. Si era el dueño registrado del workspace, el más antiguo de los
     * restantes pasa a serlo.
     */
    private void handOverIfLastManager(Workspace workspace, UUID managerUserId) {
        List<WorkspaceMember> otherManagers = memberRepository.findOthersWithRole(
                workspace.getId(), WorkspaceRole.OWNER, managerUserId);
        if (otherManagers.isEmpty()) {
            throw new ConflictException(LAST_MANAGER_MESSAGE);
        }
        if (workspace.getOwner().getId().equals(managerUserId)) {
            workspace.setOwner(otherManagers.getFirst().getUser());
        }
    }

    private Workspace lockWorkspace(String slug) {
        return workspaceRepository.findBySlugForUpdate(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado"));
    }

    private Access accessOf(Workspace workspace, UUID userId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .map(Access::of)
                .orElseThrow(() -> new ForbiddenException("No eres miembro de este workspace"));
    }

    private WorkspaceMember findMember(Workspace workspace, UUID userId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("El usuario no es miembro del workspace"));
    }

    private static void ensureNotReserved(String slug) {
        if (SlugUtils.isReserved(slug)) {
            throw new BadRequestException("El slug '" + slug + "' está reservado, elige otro");
        }
    }

    /** «Mi Equipo» → «mi-equipo», o «mi-equipo-2», «mi-equipo-3»… si ya existe. */
    private String generateUniqueSlug(String name) {
        String base = SlugUtils.slugify(name);
        if (base.length() < 3) {
            base = (base + "-workspace").replaceAll("^-", "");
        }
        String candidate = base;
        int suffix = 2;
        while (SlugUtils.isReserved(candidate) || workspaceRepository.existsBySlug(candidate)) {
            String tail = "-" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 48 - tail.length())) + tail;
        }
        return candidate;
    }
}
