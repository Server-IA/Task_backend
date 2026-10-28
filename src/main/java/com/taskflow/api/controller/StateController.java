package com.taskflow.api.controller;

import com.taskflow.api.dto.request.CreateStateRequest;
import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.service.StateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

@Tag(name = "Estados", description = "Columnas del Kanban de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/states")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class StateController {

    private final StateService stateService;

    @Operation(summary = "Listar estados (ordenados por posición)")
    @GetMapping
    public List<StateResponse> getStates(@PathVariable String slug, @PathVariable UUID projectId) {
        return stateService.getStates(slug, projectId);
    }

    @Operation(summary = "Crear estado (project manager)")
    @PostMapping
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.CREATED)
    public StateResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                                @Valid @RequestBody CreateStateRequest request) {
        return stateService.create(slug, projectId, request);
    }

    @Operation(summary = "Editar o reordenar estado (project manager)")
    @PatchMapping("/{stateId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public StateResponse update(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID stateId,
                                @Valid @RequestBody UpdateStateRequest request) {
        return stateService.update(slug, projectId, stateId, request);
    }

    @Operation(summary = "Eliminar estado (project manager)",
            description = "No se puede si tiene tareas o si es el único estado.")
    @DeleteMapping("/{stateId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID stateId) {
        stateService.delete(slug, projectId, stateId);
    }
}
