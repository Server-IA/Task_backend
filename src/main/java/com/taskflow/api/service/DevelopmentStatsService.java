package com.taskflow.api.service;

import com.taskflow.api.dto.response.DevelopmentStatsResponse;

import java.util.UUID;

public interface DevelopmentStatsService {

    /**
     * Pull requests y ejecuciones de GitHub Actions de los repositorios enlazados al proyecto en las últimas
     * {@code weeks} semanas (4, 8 o 12), más los pull requests abiertos ahora.
     */
    DevelopmentStatsResponse getStats(String slug, UUID projectId, int weeks);
}
