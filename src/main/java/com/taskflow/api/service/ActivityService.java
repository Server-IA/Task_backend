package com.taskflow.api.service;

import com.taskflow.api.dto.request.ActivityFeedType;
import com.taskflow.api.dto.response.ActivityFeedItemResponse;
import com.taskflow.api.dto.response.ActivityResponse;
import com.taskflow.api.dto.response.ActivitySummaryResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;

import java.util.List;
import java.util.UUID;

public interface ActivityService {

    List<ActivityResponse> getActivity(String slug, UUID projectId, UUID issueId);

    /** Registra un cambio en el historial del issue. Lo usan los demás servicios dentro de su transacción. */
    void record(Issue issue, User actor, ActivityField field, String oldValue, String newValue);

    /**
     * Feed de actividad del workspace (lo más reciente primero), solo de los proyectos a los que el usuario
     * tiene acceso. {@code projectId} y {@code actorId} son filtros opcionales.
     */
    PageResponse<ActivityFeedItemResponse> getWorkspaceFeed(String slug, UUID userId, ActivityFeedType type,
                                                            UUID projectId, UUID actorId, int page, int size);

    /** Resumen de la actividad de los últimos {@code days} días en los proyectos accesibles. */
    ActivitySummaryResponse getWorkspaceSummary(String slug, UUID userId, int days);
}
