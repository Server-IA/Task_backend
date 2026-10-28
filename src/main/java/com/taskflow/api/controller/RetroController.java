package com.taskflow.api.controller;

import com.taskflow.api.dto.request.RetroNoteRequest;
import com.taskflow.api.dto.request.UpdateRetroNoteRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.RetroNoteResponse;
import com.taskflow.api.dto.response.RetroResponse;
import com.taskflow.api.dto.response.RetroVoteResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.RetroService;
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

import java.util.UUID;

@Tag(name = "Retrospectivas", description = "Retrospectiva de un ciclo: notas en tres columnas, votos y acciones")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/cycles/{cycleId}/retro")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class RetroController {

    private final RetroService retroService;

    @Operation(summary = "Ver retrospectiva",
            description = "started = está abierta (si no, notes viene vacío). summary: completadas, total (incluidas "
                    + "las que el cierre movió), movidas a otro ciclo y al backlog, y cycle time. myVotesLeft = mis "
                    + "votos restantes (5 por retrospectiva). previousActions = acciones de la retrospectiva del ciclo "
                    + "cerrado anterior, con su tarea si se convirtieron.")
    @GetMapping
    public RetroResponse getRetro(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                                  @AuthenticationPrincipal UserPrincipal me) {
        return retroService.getRetro(slug, projectId, cycleId, me.getId());
    }

    @Operation(summary = "Abrir retrospectiva (project manager)",
            description = "También se abre al cerrar el ciclo con startRetro. Si ya estaba abierta, no cambia nada.")
    @PostMapping("/start")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public RetroResponse start(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                               @AuthenticationPrincipal UserPrincipal me) {
        return retroService.start(slug, projectId, cycleId, me.getId());
    }

    @Operation(summary = "Agregar nota",
            description = "column: WENT_WELL (qué salió bien), IMPROVE (qué mejorar) o ACTION (acciones); text de 1 a "
                    + "500. Solo con la retrospectiva abierta y sin terminar (409).")
    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public RetroNoteResponse createNote(@PathVariable String slug, @PathVariable UUID projectId,
                                        @PathVariable UUID cycleId, @AuthenticationPrincipal UserPrincipal me,
                                        @Valid @RequestBody RetroNoteRequest request) {
        return retroService.createNote(slug, projectId, cycleId, me.getId(), request);
    }

    @Operation(summary = "Editar nota",
            description = "Solo su autor o el project manager (403), con la retrospectiva sin terminar (409).")
    @PatchMapping("/notes/{noteId}")
    public RetroNoteResponse updateNote(@PathVariable String slug, @PathVariable UUID projectId,
                                        @PathVariable UUID cycleId, @PathVariable UUID noteId,
                                        @AuthenticationPrincipal UserPrincipal me,
                                        @Valid @RequestBody UpdateRetroNoteRequest request) {
        return retroService.updateNote(slug, projectId, cycleId, noteId, me.getId(), request);
    }

    @Operation(summary = "Eliminar nota",
            description = "Solo su autor o el project manager (403), con la retrospectiva sin terminar (409).")
    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                           @PathVariable UUID noteId, @AuthenticationPrincipal UserPrincipal me) {
        retroService.deleteNote(slug, projectId, cycleId, noteId, me.getId());
    }

    @Operation(summary = "Votar nota",
            description = "Un voto por nota y 5 por retrospectiva: 409 si ya votaste por ella o no te quedan votos, "
                    + "o si la retrospectiva no está abierta.")
    @PostMapping("/notes/{noteId}/vote")
    public RetroVoteResponse vote(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                                  @PathVariable UUID noteId, @AuthenticationPrincipal UserPrincipal me) {
        return retroService.vote(slug, projectId, cycleId, noteId, me.getId());
    }

    @Operation(summary = "Quitar mi voto",
            description = "Si no habías votado, no cambia nada. 409 si la retrospectiva no está abierta.")
    @DeleteMapping("/notes/{noteId}/vote")
    public RetroVoteResponse unvote(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                                    @PathVariable UUID noteId, @AuthenticationPrincipal UserPrincipal me) {
        return retroService.unvote(slug, projectId, cycleId, noteId, me.getId());
    }

    @Operation(summary = "Convertir acción en tarea (project manager)",
            description = "Solo notas ACTION (400). Crea la tarea en el primer estado «sin empezar» (UNSTARTED) del "
                    + "proyecto, o en el estado por defecto si no hay, con el texto como título y una descripción que "
                    + "apunta a la retrospectiva. 409 si ya se convirtió.")
    @PostMapping("/notes/{noteId}/convert")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse convert(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                                 @PathVariable UUID noteId, @AuthenticationPrincipal UserPrincipal me) {
        return retroService.convert(slug, projectId, cycleId, noteId, me.getId());
    }

    @Operation(summary = "Terminar retrospectiva (project manager)",
            description = "Desde entonces las notas y los votos son de solo lectura. 409 si no ha empezado.")
    @PostMapping("/finish")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public RetroResponse finish(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                                @AuthenticationPrincipal UserPrincipal me) {
        return retroService.finish(slug, projectId, cycleId, me.getId());
    }
}
