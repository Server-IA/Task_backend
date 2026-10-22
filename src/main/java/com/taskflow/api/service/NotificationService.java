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

    /** Avisa a los asignados y al creador del issue (menos a quien comentó). */
    void notifyCommented(Issue issue, User actor);
}
