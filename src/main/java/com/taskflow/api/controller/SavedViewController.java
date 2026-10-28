package com.taskflow.api.controller;

import com.taskflow.api.dto.request.CreateSavedViewRequest;
import com.taskflow.api.dto.request.UpdateSavedViewRequest;
import com.taskflow.api.dto.response.SavedViewResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.SavedViewService;
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

@Tag(name = "Vistas guardadas", description = "Filtros guardados de las tareas de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/views")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class SavedViewController {

    private final SavedViewService viewService;

    @Operation(summary = "Listar vistas",
            description = "Las compartidas del proyecto y luego mis vistas personales, cada grupo por position. "
                    + "mine = la creé yo.")
    @GetMapping
    public List<SavedViewResponse> getViews(@PathVariable String slug, @PathVariable UUID projectId,
                                            @AuthenticationPrincipal UserPrincipal me) {
        return viewService.getViews(slug, projectId, me.getId());
    }

    @Operation(summary = "Guardar vista",
            description = "Personal por defecto; shared: true solo lo puede usar el project manager (403). filters: "
                    + "{ state, priority, assignee, label, search, view: list|board, sort } (ej. sort \"dueDate,asc\").")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedViewResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                                    @AuthenticationPrincipal UserPrincipal me,
                                    @Valid @RequestBody CreateSavedViewRequest request) {
        return viewService.create(slug, projectId, me.getId(), request);
    }

    @Operation(summary = "Editar, compartir o reordenar vista",
            description = "Edición parcial. Una vista personal solo la edita su dueño (las de otros dan 404); una "
                    + "compartida, el project manager (403). Compartir una vista también es solo del project manager. "
                    + "position = nuevo índice (desde 0) dentro de su grupo.")
    @PatchMapping("/{viewId}")
    public SavedViewResponse update(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID viewId,
                                    @AuthenticationPrincipal UserPrincipal me,
                                    @Valid @RequestBody UpdateSavedViewRequest request) {
        return viewService.update(slug, projectId, viewId, me.getId(), request);
    }

    @Operation(summary = "Eliminar vista",
            description = "Misma regla que al editar: su dueño o, si es compartida, el project manager.")
    @DeleteMapping("/{viewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID viewId,
                       @AuthenticationPrincipal UserPrincipal me) {
        viewService.delete(slug, projectId, viewId, me.getId());
    }
}
