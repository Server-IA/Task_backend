package com.taskflow.api.controller;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

@Tag(name = "Issues", description = "Tareas de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/issues")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    @Operation(summary = "Listar issues con filtros y paginación",
            description = "Orden por defecto: sortOrder (el del Kanban). Ej.: ?state=…&priority=HIGH&search=login&page=0&size=20")
    @GetMapping
    public PageResponse<IssueResponse> getIssues(@PathVariable String slug, @PathVariable UUID projectId,
                                                 @ParameterObject IssueFilter filter,
                                                 @ParameterObject @PageableDefault(size = 20, sort = "sortOrder")
                                                 Pageable pageable) {
        return issueService.getIssues(slug, projectId, filter, pageable);
    }

    @Operation(summary = "Crear issue")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                                @AuthenticationPrincipal UserPrincipal me,
                                @Valid @RequestBody CreateIssueRequest request) {
        return issueService.create(slug, projectId, me.getId(), request);
    }

    @Operation(summary = "Ver issue")
    @GetMapping("/{issueId}")
    public IssueResponse get(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId) {
        return issueService.get(slug, projectId, issueId);
    }

    @Operation(summary = "Editar issue", description = "Edición parcial: los campos nulos no se modifican.")
    @PatchMapping("/{issueId}")
    public IssueResponse update(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                                @AuthenticationPrincipal UserPrincipal me,
                                @Valid @RequestBody UpdateIssueRequest request) {
        return issueService.update(slug, projectId, issueId, me.getId(), request);
    }

    @Operation(summary = "Mover en el Kanban (estado + orden)",
            description = "afterIssueId = tarjeta que queda arriba; beforeIssueId = tarjeta que queda abajo.")
    @PatchMapping("/{issueId}/move")
    public IssueResponse move(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                              @AuthenticationPrincipal UserPrincipal me,
                              @Valid @RequestBody MoveIssueRequest request) {
        return issueService.move(slug, projectId, issueId, me.getId(), request);
    }

    @Operation(summary = "Eliminar issue", description = "Sus sub-issues se conservan sin padre.")
    @DeleteMapping("/{issueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId) {
        issueService.delete(slug, projectId, issueId);
    }

    @Operation(summary = "Sub-issues")
    @GetMapping("/{issueId}/sub-issues")
    public List<IssueResponse> getSubIssues(@PathVariable String slug, @PathVariable UUID projectId,
                                            @PathVariable UUID issueId) {
        return issueService.getSubIssues(slug, projectId, issueId);
    }
}
