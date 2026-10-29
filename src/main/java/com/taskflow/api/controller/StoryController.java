package com.taskflow.api.controller;

import com.taskflow.api.dto.request.SplitStoryRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.StoryResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.StoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Historias", description = "Historias de usuario: listado, partir en tareas, cerrar y reabrir")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}")
@RequiredArgsConstructor
public class StoryController {

    private final StoryService storyService;
    private final IssueService issueService;

    @Operation(summary = "Historias del proyecto",
            description = "Abiertas y cerradas, de la más nueva a la más vieja, cada una con su progreso (en la "
                    + "historia: storyTaskCount, storyTaskDoneCount, storyTaskStartedCount, openBugCount, "
                    + "bugsAfterCloseCount, readyForReview, closedAt, closedBy), las personas de sus tareas, sus tareas "
                    + "y bugs (afterClose = bug creado después de cerrarla) y sus relacionadas del proyecto. La ve "
                    + "cualquier miembro del proyecto.")
    @GetMapping("/stories")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<StoryResponse> getStories(@PathVariable String slug, @PathVariable UUID projectId) {
        return storyService.getStories(slug, projectId);
    }

    @Operation(summary = "Partir una historia en tareas (project manager)",
            description = "Crea todas las líneas en una sola transacción como hijas de la historia: type TASK (por "
                    + "defecto) o BUG, asignados y prioridad (sin ella, la de la historia). Heredan el ciclo abierto, "
                    + "los módulos y las etiquetas de la historia y nacen en el primer estado sin empezar. Con tareas "
                    + "en una historia cerrada: reopen=true la reabre; si no, 409 con code STORY_CLOSED.")
    @PostMapping("/issues/{issueId}/split")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public List<IssueResponse> split(@PathVariable String slug, @PathVariable UUID projectId,
                                     @PathVariable UUID issueId, @AuthenticationPrincipal UserPrincipal me,
                                     @Valid @RequestBody SplitStoryRequest request) {
        return issueService.split(slug, projectId, issueId, me.getId(), request);
    }

    @Operation(summary = "Cerrar una historia (project manager)",
            description = "La pasa al primer estado completado del proyecto y guarda quién y cuándo (closedBy, "
                    + "closedAt). 400 si no es una historia; 409 si ya está cerrada. Los criterios sin marcar los "
                    + "confirma el cliente antes.")
    @PostMapping("/issues/{issueId}/close")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public IssueResponse close(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                               @AuthenticationPrincipal UserPrincipal me) {
        return issueService.closeStory(slug, projectId, issueId, me.getId());
    }

    @Operation(summary = "Reabrir una historia (project manager)",
            description = "La vuelve al primer estado en curso del proyecto; queda en su historial. 409 si no está "
                    + "cerrada.")
    @PostMapping("/issues/{issueId}/reopen")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public IssueResponse reopen(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                                @AuthenticationPrincipal UserPrincipal me) {
        return issueService.reopenStory(slug, projectId, issueId, me.getId());
    }
}
