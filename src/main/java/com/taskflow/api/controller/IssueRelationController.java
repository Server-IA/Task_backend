package com.taskflow.api.controller;

import com.taskflow.api.dto.request.CreateIssueRelationRequest;
import com.taskflow.api.dto.response.IssueRelationResponse;
import com.taskflow.api.dto.response.IssueRelationsResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.IssueRelationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Dependencias", description = "Tareas que bloquean, están bloqueadas o se relacionan con otra tarea")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/issues/{issueId}/relations")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class IssueRelationController {

    private final IssueRelationService relationService;

    @Operation(summary = "Dependencias de la tarea",
            description = "blocks = tareas que esta bloquea; blockedBy = tareas que la bloquean; related = "
                    + "relacionadas. La otra tarea puede ser de otro proyecto del workspace (issue.project). Cada "
                    + "lista va ordenada por código: primero las de este proyecto. accessible = false si no tengo "
                    + "acceso al proyecto de la otra tarea: entonces viene sin título ni asignados.")
    @GetMapping
    public IssueRelationsResponse getRelations(@PathVariable String slug, @PathVariable UUID projectId,
                                               @PathVariable UUID issueId, @AuthenticationPrincipal UserPrincipal me) {
        return relationService.getRelations(slug, projectId, issueId, me.getId());
    }

    @Operation(summary = "Agregar dependencia",
            description = "type: BLOCKS (esta tarea bloquea a issueId), BLOCKED_BY (issueId bloquea a esta) o RELATES. "
                    + "La otra tarea puede ser de cualquier proyecto del workspace al que tenga acceso (si no, 400). "
                    + "Responde 409 si es la misma tarea, si la relación ya existe o si las dos tareas quedarían "
                    + "bloqueándose mutuamente. Devuelve la relación vista desde esta tarea y la registra en el "
                    + "historial de ambas.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssueRelationResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                                        @PathVariable UUID issueId, @AuthenticationPrincipal UserPrincipal me,
                                        @Valid @RequestBody CreateIssueRelationRequest request) {
        return relationService.create(slug, projectId, issueId, me.getId(), request);
    }

    @Operation(summary = "Quitar dependencia",
            description = "relationId debe ser una relación de esta tarea (si no, 404). Se registra en el historial "
                    + "de ambas tareas.")
    @DeleteMapping("/{relationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                       @PathVariable UUID relationId, @AuthenticationPrincipal UserPrincipal me) {
        relationService.delete(slug, projectId, issueId, relationId, me.getId());
    }
}
