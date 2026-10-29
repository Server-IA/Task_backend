package com.taskflow.api.controller;

import com.taskflow.api.dto.request.ActivityFeedType;
import com.taskflow.api.dto.response.ActivityFeedItemResponse;
import com.taskflow.api.dto.response.ActivitySummaryResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.ActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Actividad", description = "Feed de actividad del workspace")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/activity")
@PreAuthorize("@workspaceSecurity.isMember(#slug)")
@RequiredArgsConstructor
public class WorkspaceActivityController {

    private final ActivityService activityService;

    @Operation(summary = "Feed de actividad (lo más reciente primero)",
            description = "Historial de las tareas de los proyectos a los que tengo acceso. type: all (todo), "
                    + "state (cambios de estado, incluido completar), comments (comentarios: newValue trae el texto, "
                    + "recortado a 240 caracteres) o assignments (cambios de asignados). Tamaño máximo de página: 100. "
                    + "Paginación por cursor: nextCursor se pasa como before=<createdAt>,<id> para pedir lo siguiente; "
                    + "con before se ignora page y totalElements y totalPages son -1.")
    @GetMapping
    public PageResponse<ActivityFeedItemResponse> getFeed(
            @PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
            @Parameter(description = "Página (desde 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máximo 100)") @RequestParam(defaultValue = "30") int size,
            @Parameter(description = "Tipo de actividad", schema = @Schema(
                    allowableValues = {"all", "state", "comments", "assignments"}, defaultValue = "all"))
            @RequestParam(defaultValue = "all") String type,
            @Parameter(description = "Solo este proyecto") @RequestParam(required = false) UUID projectId,
            @Parameter(description = "Solo los cambios de esta persona") @RequestParam(required = false) UUID actorId,
            @Parameter(description = "Cursor: el nextCursor de la página anterior (<createdAt>,<id>)")
            @RequestParam(required = false) String before) {
        return activityService.getWorkspaceFeed(slug, me.getId(), ActivityFeedType.from(type), projectId, actorId,
                page, size, before);
    }

    @Operation(summary = "Resumen de actividad de los últimos días",
            description = "changes = entradas del historial de cualquier tipo; comments = comentarios; completed y "
                    + "created = tareas completadas y creadas; byProject = entradas por proyecto, de más a menos.")
    @GetMapping("/summary")
    public ActivitySummaryResponse getSummary(
            @PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
            @Parameter(description = "Días hacia atrás desde ahora (1 a 365)") @RequestParam(defaultValue = "7") int days) {
        return activityService.getWorkspaceSummary(slug, me.getId(), days);
    }
}
