package com.taskflow.api.service;

import com.taskflow.api.dto.response.ProjectStatsResponse;

import java.util.UUID;

public interface ProjectStatsService {

    /** Estadísticas del proyecto; {@code weeks} (4, 8 o 12) es el periodo del throughput y del cycle time. */
    ProjectStatsResponse getStats(String slug, UUID projectId, int weeks);
}
