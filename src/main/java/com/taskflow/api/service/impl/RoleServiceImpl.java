package com.taskflow.api.service.impl;

import com.taskflow.api.repository.WorkspaceInvitationRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import com.taskflow.api.dto.request.RolePermissionsRequest;
import com.taskflow.api.dto.request.RoleRequest;
import com.taskflow.api.dto.request.UpdateRoleRequest;
import com.taskflow.api.dto.response.PermissionCatalogResponse;
import com.taskflow.api.dto.response.RoleResponse;
import com.taskflow.api.entity.CustomRole;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.entity.enums.PermissionGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.i18n.Language;
import com.taskflow.api.repository.CustomRoleRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.security.AccessControl;
import com.taskflow.api.security.AccessControl.Access;
import com.taskflow.api.service.RoleService;
import com.taskflow.api.util.HumanNames;
import com.taskflow.api.util.VisibleText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    /** Roles que puede crear un workspace, además de los dos de fábrica. */
    static final int MAX_CUSTOM_ROLES = 20;
    static final String LIMIT_MESSAGE = "Un workspace puede tener hasta " + MAX_CUSTOM_ROLES + " roles propios";
    static final String MANAGER_DESCRIPTION = "Tiene todos los permisos y es quien puede eliminar el workspace.";
    static final String MEMBER_DESCRIPTION = "El rol con el que entra quien es invitado sin elegirle otro.";

    private final CustomRoleRepository roleRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AccessControl accessControl;
    private final ResourceLookup lookup;

    @Override
    public PermissionCatalogResponse catalog() {
        // Etiquetas y descripciones en el idioma de la petición
        Language language = Language.current();
        List<PermissionCatalogResponse.Group> groups = new ArrayList<>();
        for (PermissionGroup group : PermissionGroup.values()) {
            groups.add(new PermissionCatalogResponse.Group(group.name(), language.t(group.getLabel()),
                    Arrays.stream(Permission.values())
                            .filter(permission -> permission.getGroup() == group)
                            .map(permission -> new PermissionCatalogResponse.Item(permission.name(),
                                    language.t(permission.getLabel()), language.t(permission.getDescription()),
                                    Permission.MEMBER_DEFAULTS.contains(permission)))
                            .toList()));
        }
        return new PermissionCatalogResponse(groups);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> list(String slug) {
        Workspace workspace = lookup.workspace(slug);
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : memberRepository.countByCustomRole(workspace.getId())) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        List<RoleResponse> roles = new ArrayList<>();
        roles.add(new RoleResponse(RoleResponse.Kind.MANAGER.name(), null, RoleResponse.Kind.MANAGER,
                AccessControl.managerRoleName(), Language.current().t(MANAGER_DESCRIPTION),
                Permission.names(Permission.ALL),
                memberRepository.countWithBaseRole(workspace.getId(), WorkspaceRole.OWNER)));
        roles.add(memberRole(workspace));
        for (CustomRole role : roleRepository.findAllByWorkspace(workspace.getId())) {
            roles.add(toResponse(role, counts.getOrDefault(role.getId(), 0L)));
        }
        return roles;
    }

    @Override
    @Transactional
    public RoleResponse create(String slug, UUID actorId, RoleRequest request) {
        // Con la fila del workspace bloqueada: dos creaciones a la vez no pasan juntas del máximo ni repiten nombre
        Workspace workspace = lockedWorkspace(slug);
        if (roleRepository.countByWorkspaceId(workspace.getId()) >= MAX_CUSTOM_ROLES) {
            throw new ConflictException(LIMIT_MESSAGE);
        }
        String name = checkedName(workspace, request.name(), null, null);
        Set<Permission> permissions = parse(request.permissions());
        ensureMayGrant(slug, actorId, permissions);

        CustomRole role = new CustomRole();
        role.setWorkspace(workspace);
        role.setName(name);
        role.setDescription(cleanDescription(request.description()));
        role.setPermissionSet(permissions);
        return toResponse(roleRepository.saveAndFlush(role), 0);
    }

    @Override
    @Transactional
    public RoleResponse update(String slug, UUID actorId, UUID roleId, UpdateRoleRequest request) {
        Workspace workspace = lockedWorkspace(slug);
        CustomRole role = findRole(workspace, roleId);
        // Un rol con permisos que a quien edita le faltan no se toca: podría quitárselos a otros o ponérselo después
        ensureMayGrant(slug, actorId, role.permissionSet());
        if (request.name() != null) {
            role.setName(checkedName(workspace, request.name(), role.getId(), role.getName()));
        }
        if (request.description() != null) {
            role.setDescription(cleanDescription(request.description()));
        }
        if (request.permissions() != null) {
            Set<Permission> permissions = parse(request.permissions());
            ensureMayGrant(slug, actorId, permissions);
            role.setPermissionSet(permissions);
        }
        long members = memberRepository.countByCustomRole(workspace.getId()).stream()
                .filter(row -> role.getId().equals(row[0]))
                .mapToLong(row -> (Long) row[1])
                .sum();
        return toResponse(roleRepository.saveAndFlush(role), members);
    }

    @Override
    @Transactional
    public RoleResponse updateMemberPermissions(String slug, UUID actorId, RolePermissionsRequest request) {
        Workspace workspace = lockedWorkspace(slug);
        Set<Permission> permissions = parse(request.permissions());
        ensureMayGrant(slug, actorId, workspace.memberPermissionSet());
        ensureMayGrant(slug, actorId, permissions);
        workspace.setMemberPermissionSet(permissions);
        return memberRole(workspace);
    }

    @Override
    @Transactional
    public void delete(String slug, UUID actorId, UUID roleId) {
        Workspace workspace = lockedWorkspace(slug);
        CustomRole role = findRole(workspace, roleId);
        ensureMayGrant(slug, actorId, role.permissionSet());
        // Quienes lo tienen (y las invitaciones pendientes con él) pasan a «Miembro»: si eso les da permisos que a quien
        // elimina le faltan, sería una forma de subirlos (o de subirse, si el rol es el suyo)
        boolean inUse = memberRepository.countByCustomRoleId(role.getId()) > 0
                || invitationRepository.countByCustomRoleIdAndAcceptedFalse(role.getId()) > 0;
        if (inUse) {
            ensureMayGrant(slug, actorId, workspace.memberPermissionSet());
        }
        // La base de datos deja en «Miembro» a quienes lo tenían y a las invitaciones pendientes (ON DELETE SET NULL)
        roleRepository.delete(role);
    }

    // ---------------------------------------------------------------- reglas

    /**
     * Quien no es project manager solo maneja permisos que su propio rol tiene.
     */
    private void ensureMayGrant(String slug, UUID actorId, Set<Permission> permissions) {
        Access actor = accessControl.find(slug, actorId)
                .orElseThrow(() -> new ForbiddenException("No eres miembro de este workspace"));
        if (actor.isManager() || actor.covers(permissions)) {
            return;
        }
        // Los nombres de los permisos, ya en el idioma de la petición (el resto del mensaje se traduce al responder)
        Language language = Language.current();
        String missing = permissions.stream()
                .filter(permission -> !actor.can(permission))
                .map(permission -> language.t(permission.getLabel()))
                .collect(Collectors.joining(", "));
        throw new ForbiddenException("No puedes dar ni quitar permisos que tu rol no tiene: " + missing);
    }

    /** Los permisos pedidos; 400 si alguno no existe. */
    static Set<Permission> parse(List<String> names) {
        Set<Permission> permissions = Permission.fromNames(names);
        List<String> unknown = names.stream()
                .filter(name -> Arrays.stream(Permission.values()).noneMatch(permission -> permission.name().equals(name)))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Permiso desconocido: " + String.join(", ", unknown));
        }
        return permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : permissions;
    }

    /**
     * El nombre limpio; 409 si ya lo usa otro rol del workspace o es el de un rol de fábrica en cualquiera de los
     * idiomas («Project manager», «Miembro», «Member»). Un rol que ya se llamaba así antes de que el nombre quedara
     * reservado ({@code currentName}) puede conservarlo.
     */
    private String checkedName(Workspace workspace, String rawName, UUID excludedId, String currentName) {
        String name = HumanNames.required(rawName, "name");
        boolean reserved = AccessControl.isFactoryRoleName(name) && !name.equalsIgnoreCase(currentName);
        if (reserved || roleRepository.existsByName(workspace.getId(), name, excludedId)) {
            throw new ConflictException("Ya hay un rol llamado «" + name + "»");
        }
        return name;
    }

    private static String cleanDescription(String description) {
        String text = VisibleText.strip(description);
        return text == null || text.isEmpty() ? null : text;
    }

    /**
     * El workspace leído con su fila bloqueada hasta el final de la transacción: las validaciones (permisos de
     * «Miembro», nombres, cantidad de roles) se hacen sobre lo que hay después de esperar a quien lo estaba cambiando.
     */
    private Workspace lockedWorkspace(String slug) {
        return workspaceRepository.findBySlugForUpdate(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado"));
    }

    private CustomRole findRole(Workspace workspace, UUID roleId) {
        return roleRepository.findByIdAndWorkspaceId(roleId, workspace.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Rol no encontrado"));
    }

    private RoleResponse memberRole(Workspace workspace) {
        return new RoleResponse(RoleResponse.Kind.MEMBER.name(), null, RoleResponse.Kind.MEMBER,
                AccessControl.memberRoleName(), Language.current().t(MEMBER_DESCRIPTION),
                Permission.names(workspace.memberPermissionSet()),
                memberRepository.countWithBaseRole(workspace.getId(), WorkspaceRole.MEMBER));
    }

    private static RoleResponse toResponse(CustomRole role, long memberCount) {
        return new RoleResponse(role.getId().toString(), role.getId(), RoleResponse.Kind.CUSTOM, role.getName(),
                role.getDescription(), Permission.names(role.permissionSet()), memberCount);
    }
}
