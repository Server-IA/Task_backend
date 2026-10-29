package com.taskflow.api.controller;

import com.taskflow.api.dto.response.ProjectStatsResponse;
import com.taskflow.api.service.ProjectStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Estadísticas", description = "Métricas de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/stats")
@RequiredArgsConstructor
public class ProjectStatsController {

    private final ProjectStatsService statsService;

    @Operation(summary = "Estadísticas del proyecto",
            description = "Tareas abiertas, creadas y completadas por semana (desde el lunes, hora de Bogotá), "
                    + "vencidas, cycle time (mediana de días de En curso a Hecho: de startedAt a completedAt; las "
                    + "que nunca pasaron por un estado STARTED no cuentan), throughput semanal, burndown del ciclo en "
                    + "curso, abiertas por estado, carga por persona y tareas estancadas (más de 14 días sin "
                    + "cambios). weeks fija el periodo del throughput y del cycle time. assignee («+ persona», acepta "
                    + "varios) limita todas las métricas a las tareas asignadas a esas personas, y la carga a esas "
                    + "personas.")
    @GetMapping
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public ProjectStatsResponse getStats(
            @PathVariable String slug, @PathVariable UUID projectId,
            @Parameter(description = "Semanas del periodo: 4, 8 o 12 (otro valor responde 400)")
            @RequestParam(defaultValue = "8") int weeks,
            @Parameter(description = "Id(s) de usuario asignado")
            @RequestParam(required = false) List<UUID> assignee) {
        return statsService.getStats(slug, projectId, weeks, assignee);
    }
}
