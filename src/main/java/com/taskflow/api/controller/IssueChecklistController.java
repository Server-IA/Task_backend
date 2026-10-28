package com.taskflow.api.controller;

import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.UpdateChecklistItemRequest;
import com.taskflow.api.dto.response.ChecklistItemResponse;
import com.taskflow.api.service.IssueChecklistService;
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

@Tag(name = "Checklist", description = "Criterios de aceptación y pasos de la checklist de una tarea")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/issues/{issueId}/checklist")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class IssueChecklistController {

    private final IssueChecklistService checklistService;

    @Operation(summary = "Criterios y checklist de la tarea",
            description = "Primero los criterios de aceptación (CRITERION) y luego los pasos (CHECK), cada grupo por "
                    + "position.")
    @GetMapping
    public List<ChecklistItemResponse> getItems(@PathVariable String slug, @PathVariable UUID projectId,
                                                @PathVariable UUID issueId) {
        return checklistService.getItems(slug, projectId, issueId);
    }

    @Operation(summary = "Agregar criterio o paso",
            description = "Queda al final de los de su tipo. Una tarea admite como máximo 100 elementos (409).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistItemResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                                        @PathVariable UUID issueId, @Valid @RequestBody ChecklistItemRequest request) {
        return checklistService.create(slug, projectId, issueId, request);
    }

    @Operation(summary = "Editar, marcar o reordenar",
            description = "Edición parcial. position = nuevo índice (desde 0) entre los elementos del mismo tipo; los "
                    + "demás se renumeran.")
    @PatchMapping("/{itemId}")
    public ChecklistItemResponse update(@PathVariable String slug, @PathVariable UUID projectId,
                                        @PathVariable UUID issueId, @PathVariable UUID itemId,
                                        @Valid @RequestBody UpdateChecklistItemRequest request) {
        return checklistService.update(slug, projectId, issueId, itemId, request);
    }

    @Operation(summary = "Eliminar criterio o paso")
    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                       @PathVariable UUID itemId) {
        checklistService.delete(slug, projectId, issueId, itemId);
    }
}
