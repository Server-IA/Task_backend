package com.taskflow.api.controller;

import com.taskflow.api.dto.request.LabelRequest;
import com.taskflow.api.dto.response.LabelResponse;
import com.taskflow.api.service.LabelService;
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

@Tag(name = "Etiquetas", description = "Etiquetas de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/labels")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class LabelController {

    private final LabelService labelService;

    @Operation(summary = "Listar etiquetas")
    @GetMapping
    public List<LabelResponse> getLabels(@PathVariable String slug, @PathVariable UUID projectId) {
        return labelService.getLabels(slug, projectId);
    }

    @Operation(summary = "Crear etiqueta (project manager)")
    @PostMapping
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.CREATED)
    public LabelResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                                @Valid @RequestBody LabelRequest request) {
        return labelService.create(slug, projectId, request);
    }

    @Operation(summary = "Editar etiqueta (project manager)")
    @PatchMapping("/{labelId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public LabelResponse update(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID labelId,
                                @Valid @RequestBody LabelRequest request) {
        return labelService.update(slug, projectId, labelId, request);
    }

    @Operation(summary = "Eliminar etiqueta (project manager)")
    @DeleteMapping("/{labelId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID labelId) {
        labelService.delete(slug, projectId, labelId);
    }
}
