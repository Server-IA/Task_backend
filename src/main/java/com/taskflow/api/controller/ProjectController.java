package com.taskflow.api.controller;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.UpdateProjectRequest;
import com.taskflow.api.dto.response.ProjectMemberResponse;
import com.taskflow.api.dto.response.ProjectResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.ProjectService;
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

@Tag(name = "Proyectos", description = "Proyectos de un workspace y sus miembros")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "Listar proyectos",
            description = "El project manager ve todos los proyectos; los MEMBER, solo aquellos de los que son miembros.")
    @GetMapping
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    public List<ProjectResponse> getProjects(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me) {
        return projectService.getProjects(slug, me.getId());
    }

    @Operation(summary = "Crear proyecto (project manager)",
            description = "Crea los estados por defecto (Backlog, Todo, In Progress, Done, Cancelled) y las plantillas "
                    + "de tarea por defecto (Reporte de bug, Historia de usuario y Tarea técnica). color es una clave "
                    + "de la paleta (TEAL, AMBER, BLUE, PINK, VIOLET, ORANGE, GREEN); sin color, el menos usado del "
                    + "workspace (entre empatados, el primero de la paleta).")
    @PostMapping
    @PreAuthorize("@workspaceSecurity.isManager(#slug)")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
                                  @Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(slug, me.getId(), request);
    }

    @Operation(summary = "Ver proyecto")
    @GetMapping("/{projectId}")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public ProjectResponse get(@PathVariable String slug, @PathVariable UUID projectId) {
        return projectService.get(slug, projectId);
    }

    @Operation(summary = "Editar proyecto (project manager)",
            description = "Edición parcial: los campos nulos no se modifican. color es una clave de la paleta.")
    @PatchMapping("/{projectId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public ProjectResponse update(@PathVariable String slug, @PathVariable UUID projectId,
                                  @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(slug, projectId, request);
    }

    @Operation(summary = "Eliminar proyecto (project manager)")
    @DeleteMapping("/{projectId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId) {
        projectService.delete(slug, projectId);
    }

    @Operation(summary = "Miembros del proyecto")
    @GetMapping("/{projectId}/members")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<ProjectMemberResponse> getMembers(@PathVariable String slug, @PathVariable UUID projectId) {
        return projectService.getMembers(slug, projectId);
    }

    @Operation(summary = "Agregar miembro al proyecto (project manager)", description = "Debe ser miembro del workspace.")
    @PostMapping("/{projectId}/members")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemberResponse addMember(@PathVariable String slug, @PathVariable UUID projectId,
                                           @Valid @RequestBody AddProjectMemberRequest request) {
        return projectService.addMember(slug, projectId, request);
    }

    @Operation(summary = "Quitar miembro del proyecto (project manager)")
    @DeleteMapping("/{projectId}/members/{userId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID userId) {
        projectService.removeMember(slug, projectId, userId);
    }
}
