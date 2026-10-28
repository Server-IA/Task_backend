package com.taskflow.api.controller;

import com.taskflow.api.dto.request.CloseCycleRequest;
import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.CycleRequest;
import com.taskflow.api.dto.request.UpdateCycleRequest;
import com.taskflow.api.dto.response.CloseCyclePreviewResponse;
import com.taskflow.api.dto.response.CloseCycleResponse;
import com.taskflow.api.dto.response.CycleResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.CycleService;
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

@Tag(name = "Ciclos", description = "Ciclos (sprints) de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/cycles")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class CycleController {

    private final CycleService cycleService;

    @Operation(summary = "Listar ciclos con su avance")
    @GetMapping
    public List<CycleResponse> getCycles(@PathVariable String slug, @PathVariable UUID projectId) {
        return cycleService.getCycles(slug, projectId);
    }

    @Operation(summary = "Crear ciclo (project manager)")
    @PostMapping
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.CREATED)
    public CycleResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                              @Valid @RequestBody CycleRequest request) {
        return cycleService.create(slug, projectId, request);
    }

    @Operation(summary = "Ver ciclo")
    @GetMapping("/{cycleId}")
    public CycleResponse get(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId) {
        return cycleService.get(slug, projectId, cycleId);
    }

    @Operation(summary = "Editar ciclo (project manager)",
            description = "En un ciclo cerrado solo se puede cambiar la descripción: cambiar el nombre o las fechas "
                    + "responde 409.")
    @PatchMapping("/{cycleId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public CycleResponse update(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                              @Valid @RequestBody UpdateCycleRequest request) {
        return cycleService.update(slug, projectId, cycleId, request);
    }

    @Operation(summary = "Eliminar ciclo (project manager)", description = "Las tareas no se borran, solo se desvinculan.")
    @DeleteMapping("/{cycleId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId) {
        cycleService.delete(slug, projectId, cycleId);
    }

    @Operation(summary = "Agregar issues al ciclo (si estaban en otro ciclo, se mueven a este)",
            description = "Un ciclo cerrado no recibe tareas (409).")
    @PostMapping("/{cycleId}/issues")
    public CycleResponse addIssues(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                                 @AuthenticationPrincipal UserPrincipal me,
                                 @Valid @RequestBody IssueIdsRequest request) {
        return cycleService.addIssues(slug, projectId, cycleId, me.getId(), request);
    }

    @Operation(summary = "Quitar un issue del ciclo")
    @DeleteMapping("/{cycleId}/issues/{issueId}")
    public CycleResponse removeIssue(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                                   @PathVariable UUID issueId, @AuthenticationPrincipal UserPrincipal me) {
        return cycleService.removeIssue(slug, projectId, cycleId, issueId, me.getId());
    }

    @Operation(summary = "Vista previa del cierre (project manager)",
            description = "Tareas completadas (grupo COMPLETED) y sin terminar (ni completadas ni canceladas) del ciclo, "
                    + "por número; cycleTimeDays = mediana de días de creación a completado de las completadas "
                    + "(null si no hay); nextCycle = siguiente ciclo sin cerrar por fecha de inicio (destino por "
                    + "defecto), o null.")
    @GetMapping("/{cycleId}/close-preview")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public CloseCyclePreviewResponse closePreview(@PathVariable String slug, @PathVariable UUID projectId,
                                                  @PathVariable UUID cycleId) {
        return cycleService.closePreview(slug, projectId, cycleId);
    }

    @Operation(summary = "Cerrar ciclo (project manager)",
            description = "Las tareas sin terminar van a moveTo (NEXT_CYCLE: al ciclo targetCycleId o, sin él, al "
                    + "siguiente; BACKLOG: salen del ciclo sin cambiar de estado), salvo las de overrides. Sin ciclo "
                    + "siguiente y con alguna tarea hacia NEXT_CYCLE responde 400. Si el ciclo terminaba después de "
                    + "hoy, su fin pasa a hoy. startRetro abre la retrospectiva. Un ciclo ya cerrado, o que aún no "
                    + "empezó, responde 409.")
    @PostMapping("/{cycleId}/close")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public CloseCycleResponse close(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID cycleId,
                                    @AuthenticationPrincipal UserPrincipal me,
                                    @Valid @RequestBody CloseCycleRequest request) {
        return cycleService.close(slug, projectId, cycleId, me.getId(), request);
    }
}
