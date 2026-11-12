package com.taskflow.api.controller;

import com.taskflow.api.dto.request.RolePermissionsRequest;
import com.taskflow.api.dto.request.RoleRequest;
import com.taskflow.api.dto.request.UpdateRoleRequest;
import com.taskflow.api.dto.response.PermissionCatalogResponse;
import com.taskflow.api.dto.response.RoleResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Roles", description = "Roles de un workspace y sus permisos")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "Catálogo de permisos",
            description = "Todos los permisos que puede tener un rol, agrupados por módulo de la aplicación.")
    @GetMapping("/permissions")
    public PermissionCatalogResponse catalog() {
        return roleService.catalog();
    }

    @Operation(summary = "Roles del workspace",
            description = "Project manager (todos los permisos), «Miembro» y los roles creados, cada uno con sus "
                    + "permisos y cuántas personas lo tienen.")
    @GetMapping("/workspaces/{slug}/roles")
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    public List<RoleResponse> getRoles(@PathVariable String slug) {
        return roleService.list(slug);
    }

    @Operation(summary = "Crear un rol",
            description = "Hasta 20 por workspace; el nombre no se repite (409). 403 si quien lo crea no es project "
                    + "manager y marca un permiso que su rol no tiene.")
    @PostMapping("/workspaces/{slug}/roles")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'ROLE_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse create(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
                               @Valid @RequestBody RoleRequest request) {
        return roleService.create(slug, me.getId(), request);
    }

    @Operation(summary = "Cambiar los permisos del rol «Miembro»")
    @PutMapping("/workspaces/{slug}/roles/member/permissions")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'ROLE_MANAGE')")
    public RoleResponse updateMemberPermissions(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
                                                @Valid @RequestBody RolePermissionsRequest request) {
        return roleService.updateMemberPermissions(slug, me.getId(), request);
    }

    @Operation(summary = "Editar un rol creado", description = "Nombre, descripción y permisos; lo que no venga no cambia.")
    @PatchMapping("/workspaces/{slug}/roles/{roleId}")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'ROLE_MANAGE')")
    public RoleResponse update(@PathVariable String slug, @PathVariable UUID roleId,
                               @AuthenticationPrincipal UserPrincipal me, @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.update(slug, me.getId(), roleId, request);
    }

    @Operation(summary = "Eliminar un rol creado",
            description = "Quienes lo tenían, y las invitaciones pendientes con él, pasan al rol «Miembro».")
    @DeleteMapping("/workspaces/{slug}/roles/{roleId}")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'ROLE_MANAGE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID roleId, @AuthenticationPrincipal UserPrincipal me) {
        roleService.delete(slug, me.getId(), roleId);
    }
}
