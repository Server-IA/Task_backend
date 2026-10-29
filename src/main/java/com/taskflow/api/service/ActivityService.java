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
     * Registra que {@code actor} editó la descripción. El editor guarda solo mientras se escribe, así que una sesión
     * de edición deja una sola entrada: si la última entrada del historial de la tarea ya es una edición de la
     * descripción de la misma persona, de hace menos de {@code DESCRIPTION_SESSION}, solo se actualiza su hora de
     * actualización (la entrada conserva la hora en que empezó).
     */
    void recordDescriptionEdit(Issue issue, User actor);

    /**
     * Feed de actividad del workspace (lo más reciente primero), solo de los proyectos a los que el usuario
     * tiene acceso. {@code projectId} y {@code actorId} son filtros opcionales.
     */
    PageResponse<ActivityFeedItemResponse> getWorkspaceFeed(String slug, UUID userId, ActivityFeedType type,
                                                            UUID projectId, UUID actorId, int page, int size);

    /** Resumen de la actividad de los últimos {@code days} días en los proyectos accesibles. */
    ActivitySummaryResponse getWorkspaceSummary(String slug, UUID userId, int days);
}
