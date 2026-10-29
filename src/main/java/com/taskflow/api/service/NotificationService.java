package com.taskflow.api.service;

import com.taskflow.api.dto.response.CountResponse;
import com.taskflow.api.dto.response.NotificationCountsResponse;
import com.taskflow.api.dto.response.NotificationResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Comment;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.NotificationType;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.UUID;

public interface NotificationService {

    /**
     * Mis notificaciones (todas o solo las no leídas) de los tipos indicados; {@code types} vacío o {@code null} =
     * todos los tipos.
     */
    PageResponse<NotificationResponse> getMyNotifications(UUID userId, boolean unreadOnly,
                                                          Collection<NotificationType> types, Pageable pageable);

    CountResponse countUnread(UUID userId);

    /** Contadores de las pestañas de la bandeja. */
    NotificationCountsResponse counts(UUID userId);

    NotificationResponse markAsRead(UUID userId, UUID notificationId);

    void markAllAsRead(UUID userId);

    /** Avisa a los nuevos asignados (menos a quien hizo la asignación). */
    void notifyAssigned(Issue issue, User actor, Collection<User> newAssignees);

    /**
     * Avisa de un comentario nuevo: a los mencionados con {@code ISSUE_MENTIONED} y a los asignados y al creador del
     * issue con {@code ISSUE_COMMENTED}, nunca a quien comentó. Quien está mencionado recibe solo la mención. Las
     * notificaciones guardan el comentario para mostrar su extracto.
     */
    void notifyCommented(Issue issue, User actor, Comment comment, Collection<User> mentioned);

    /** Avisa a los mencionados en un comentario (menos a su autor). */
    void notifyMentioned(Issue issue, User actor, Comment comment, Collection<User> mentioned);

    /**
     * Avisa con {@code ISSUE_MENTIONED} a quienes se mencionan en la descripción de la tarea (menos a quien la
     * editó). Quien llama pasa solo las personas mencionadas por primera vez.
     */
    void notifyMentionedInDescription(Issue issue, User actor, Collection<User> mentioned);

    /**
     * Avisa a los asignados de {@code blocked} (menos a quien hizo el cambio) de que ya no está bloqueada porque se
     * completó {@code blocker}.
     */
    void notifyUnblocked(Issue blocked, Issue blocker, User actor);

    /**
     * Avisa a los miembros del proyecto del ciclo (menos a quien la abrió) de que se abrió la retrospectiva
     * ({@code RETRO_STARTED}). Solo en la app: no envía correo.
     */
    void notifyRetroStarted(Cycle cycle, User actor);
}
