package com.taskflow.api.controller;

import com.taskflow.api.dto.request.IssueTemplateRequest;
import com.taskflow.api.dto.request.UpdateIssueTemplateRequest;
import com.taskflow.api.dto.response.IssueTemplateResponse;
import com.taskflow.api.service.IssueTemplateService;
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

@Tag(name = "Plantillas", description = "Plantillas de tarea de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/templates")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class IssueTemplateController {

    private final IssueTemplateService templateService;

    @Operation(summary = "Listar plantillas (en su orden)",
            description = "Al crear una tarea desde una plantilla, el frontend antepone titlePrefix al título, usa "
                    + "description, priority y labels, envía checklist en el campo checklist de la tarea y el id de "
                    + "la plantilla en templateId. usageCount = tareas creadas con la plantilla.")
    @GetMapping
    public List<IssueTemplateResponse> getTemplates(@PathVariable String slug, @PathVariable UUID projectId) {
        return templateService.getTemplates(slug, projectId);
    }

    @Operation(summary = "Crear plantilla (project manager)",
            description = "El nombre no se repite en el proyecto (409) y las etiquetas deben ser del proyecto (400). "
                    + "Queda al final de la lista.")
    @PostMapping
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueTemplateResponse create(@PathVariable String slug, @PathVariable UUID projectId,
                                        @Valid @RequestBody IssueTemplateRequest request) {
        return templateService.create(slug, projectId, request);
    }

    @Operation(summary = "Editar o reordenar plantilla (project manager)",
            description = "Edición parcial. titlePrefix o description vacíos los quitan y priority NONE quita la "
                    + "prioridad; labelIds y checklist reemplazan la lista; position = nuevo índice (desde 0).")
    @PatchMapping("/{templateId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    public IssueTemplateResponse update(@PathVariable String slug, @PathVariable UUID projectId,
                                        @PathVariable UUID templateId,
                                        @Valid @RequestBody UpdateIssueTemplateRequest request) {
        return templateService.update(slug, projectId, templateId, request);
    }

    @Operation(summary = "Eliminar plantilla (project manager)")
    @DeleteMapping("/{templateId}")
    @PreAuthorize("@workspaceSecurity.canManageProject(#slug, #projectId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID templateId) {
        templateService.delete(slug, projectId, templateId);
    }
}
