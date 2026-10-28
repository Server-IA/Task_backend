package com.taskflow.api.controller;

import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.ModuleRequest;
import com.taskflow.api.dto.request.UpdateModuleRequest;
import com.taskflow.api.dto.response.ModuleResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.ModuleService;
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

@Tag(name = "Módulos", description = "Módulos de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/modules")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;

    @Operation(summary = "Listar módulos con su avance")
    @GetMapping
    public List<ModuleResponse> getModules(@PathVariable String slug, @PathVariable UUID projectId) {
        return moduleService.getModules(slug, projectId);
    }

    @Operation(summary = "Crear módulo (project manager)")
    @PostMapping
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                              @Valid @RequestBody ModuleRequest request) {
        return moduleService.create(slug, projectId, request);
    }

    @Operation(summary = "Ver módulo")
    @GetMapping("/{moduleId}")
    public ModuleResponse get(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID moduleId) {
        return moduleService.get(slug, projectId, moduleId);
    }

    @Operation(summary = "Editar módulo (project manager)")
    @PatchMapping("/{moduleId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public ModuleResponse update(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID moduleId,
                              @Valid @RequestBody UpdateModuleRequest request) {
        return moduleService.update(slug, projectId, moduleId, request);
    }

    @Operation(summary = "Eliminar módulo (project manager)", description = "Las tareas no se borran, solo se desvinculan.")
    @DeleteMapping("/{moduleId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID moduleId) {
        moduleService.delete(slug, projectId, moduleId);
    }

    @Operation(summary = "Agregar issues al módulo")
    @PostMapping("/{moduleId}/issues")
    public ModuleResponse addIssues(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID moduleId,
                                 @AuthenticationPrincipal UserPrincipal me,
                                 @Valid @RequestBody IssueIdsRequest request) {
        return moduleService.addIssues(slug, projectId, moduleId, me.getId(), request);
    }

    @Operation(summary = "Quitar un issue del módulo")
    @DeleteMapping("/{moduleId}/issues/{issueId}")
    public ModuleResponse removeIssue(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID moduleId,
                                   @PathVariable UUID issueId, @AuthenticationPrincipal UserPrincipal me) {
        return moduleService.removeIssue(slug, projectId, moduleId, issueId, me.getId());
    }
}
