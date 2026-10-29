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
     * Registra que {@code actor} marcó ({@code checked}) o desmarcó la casilla {@code text} de la descripción. Si lo
     * último del historial es lo contrario sobre la misma casilla, de la misma persona y hace menos de
     * {@code TASK_UNDO}, se anulan: se borra esa entrada y no se registra nada.
     */
    void recordDescriptionTask(Issue issue, User actor, String text, boolean checked);

    /**
     * Feed de actividad del workspace (lo más reciente primero), solo de los proyectos a los que el usuario
     * tiene acceso. {@code projectId} y {@code actorId} son filtros opcionales.
     */
    default PageResponse<ActivityFeedItemResponse> getWorkspaceFeed(String slug, UUID userId, ActivityFeedType type,
                                                                    UUID projectId, UUID actorId, int page,
                                                                    int size) {
        return getWorkspaceFeed(slug, userId, type, projectId, actorId, page, size, null);
    }

    /**
     * Con {@code before} (un cursor {@code <createdAt>,<id>}), lo que va después de ese elemento, sin contar el total
     * ({@code page} no se usa). La respuesta trae {@code nextCursor}.
     */
    PageResponse<ActivityFeedItemResponse> getWorkspaceFeed(String slug, UUID userId, ActivityFeedType type,
                                                            UUID projectId, UUID actorId, int page, int size,
                                                            String before);

    /** Resumen de la actividad de los últimos {@code days} días en los proyectos accesibles. */
    ActivitySummaryResponse getWorkspaceSummary(String slug, UUID userId, int days);
}
