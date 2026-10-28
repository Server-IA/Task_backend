package com.taskflow.api.service;

import com.taskflow.api.dto.response.CountResponse;
import com.taskflow.api.dto.response.NotificationResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.User;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.UUID;

public interface NotificationService {

    PageResponse<NotificationResponse> getMyNotifications(UUID userId, boolean unreadOnly, Pageable pageable);

    CountResponse countUnread(UUID userId);

    NotificationResponse markAsRead(UUID userId, UUID notificationId);

    void markAllAsRead(UUID userId);

    /** Avisa a los nuevos asignados (menos a quien hizo la asignación). */
    void notifyAssigned(Issue issue, User actor, Collection<User> newAssignees);

    /**
     * Avisa de un comentario nuevo: a los mencionados con {@code ISSUE_MENTIONED} y a los asignados y al creador del
     * issue con {@code ISSUE_COMMENTED}, nunca a quien comentó. Quien está mencionado recibe solo la mención.
     */
    void notifyCommented(Issue issue, User actor, Collection<User> mentioned);

    /** Avisa a los mencionados en un comentario (menos a su autor). */
    void notifyMentioned(Issue issue, User actor, Collection<User> mentioned);

    /**
     * Avisa a los asignados de {@code blocked} (menos a quien hizo el cambio) de que ya no está bloqueada porque se
     * completó {@code blocker}.
     */
    void notifyUnblocked(Issue blocked, Issue blocker, User actor);
}
