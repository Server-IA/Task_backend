package com.taskflow.api.service;

import com.taskflow.api.dto.response.ProjectStatsResponse;

import java.util.Collection;
import java.util.UUID;

public interface ProjectStatsService {

    /**
     * Estadísticas del proyecto; {@code weeks} (4, 8 o 12) es el periodo del throughput y del cycle time. Con
     * {@code assignees} (no vacío) solo cuentan las tareas asignadas a alguna de esas personas, y la carga muestra
     * solo a esas personas.
     */
    ProjectStatsResponse getStats(String slug, UUID projectId, int weeks, Collection<UUID> assignees);
}
