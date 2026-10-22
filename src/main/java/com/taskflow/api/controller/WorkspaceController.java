package com.taskflow.api.controller;

import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.UpdateMemberRoleRequest;
import com.taskflow.api.dto.request.UpdateWorkspaceRequest;
import com.taskflow.api.dto.response.WorkspaceMemberResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.WorkspaceService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Workspaces", description = "Workspaces y sus miembros")
@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @Operation(summary = "Mis workspaces")
    @GetMapping
    public List<WorkspaceResponse> getMyWorkspaces(@AuthenticationPrincipal UserPrincipal me) {
        return workspaceService.getMyWorkspaces(me.getId());
    }

    @Operation(summary = "Crear workspace", description = "El creador queda como OWNER. Si no se envía slug, se genera.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@AuthenticationPrincipal UserPrincipal me,
                                    @Valid @RequestBody CreateWorkspaceRequest request) {
        return workspaceService.create(me.getId(), request);
    }

    @Operation(summary = "Ver workspace")
    @GetMapping("/{slug}")
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    public WorkspaceResponse get(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me) {
        return workspaceService.get(slug, me.getId());
    }

    @Operation(summary = "Editar workspace (ADMIN)")
    @PatchMapping("/{slug}")
    @PreAuthorize("@workspaceSecurity.isAdmin(#slug)")
    public WorkspaceResponse update(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
                                    @Valid @RequestBody UpdateWorkspaceRequest request) {
        return workspaceService.update(slug, me.getId(), request);
    }

    @Operation(summary = "Eliminar workspace (OWNER)", description = "Borra también sus proyectos e issues.")
    @DeleteMapping("/{slug}")
    @PreAuthorize("@workspaceSecurity.isOwner(#slug)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug) {
        workspaceService.delete(slug);
    }

    @Operation(summary = "Listar miembros")
    @GetMapping("/{slug}/members")
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    public List<WorkspaceMemberResponse> getMembers(@PathVariable String slug) {
        return workspaceService.getMembers(slug);
    }

    @Operation(summary = "Cambiar rol de un miembro (ADMIN)", description = "Roles permitidos: ADMIN o MEMBER.")
    @PatchMapping("/{slug}/members/{userId}")
    @PreAuthorize("@workspaceSecurity.isAdmin(#slug)")
    public WorkspaceMemberResponse updateMemberRole(@PathVariable String slug, @PathVariable UUID userId,
                                                    @Valid @RequestBody UpdateMemberRoleRequest request) {
        return workspaceService.updateMemberRole(slug, userId, request);
    }

    @Operation(summary = "Quitar miembro o salir del workspace",
            description = "Un ADMIN puede quitar a cualquiera menos al dueño; un miembro puede quitarse a sí mismo.")
    @DeleteMapping("/{slug}/members/{userId}")
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String slug, @PathVariable UUID userId,
                             @AuthenticationPrincipal UserPrincipal me) {
        workspaceService.removeMember(slug, me.getId(), userId);
    }
}
