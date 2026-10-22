package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.CountResponse;
import com.taskflow.api.dto.response.NotificationResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Notification;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.NotificationType;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.NotificationRepository;
import com.taskflow.api.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final int MAX_TITLE_IN_MESSAGE = 200;

    private final NotificationRepository notificationRepository;
    private final CollaborationMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getMyNotifications(UUID userId, boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = unreadOnly
                ? notificationRepository.findAllByRecipientIdAndReadFalse(userId, pageable)
                : notificationRepository.findAllByRecipientId(userId, pageable);
        return PageResponse.from(page, mapper::toNotificationResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public CountResponse countUnread(UUID userId) {
        return new CountResponse(notificationRepository.countByRecipientIdAndReadFalse(userId));
    }

    @Override
    @Transactional
    public NotificationResponse markAsRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notificación no encontrada"));
        notification.setRead(true);
        return mapper.toNotificationResponse(notification);
    }

    @Override
    @Transactional
    public void markAllAsRead(UUID userId) {
        notificationRepository.markAllAsRead(userId);
    }

    @Override
    @Transactional
    public void notifyAssigned(Issue issue, User actor, Collection<User> newAssignees) {
        String message = actor.getFullName() + " te asignó " + describe(issue);
        newAssignees.stream()
                .filter(user -> !user.getId().equals(actor.getId()))
                .forEach(user -> save(user, actor, NotificationType.ISSUE_ASSIGNED, message, issue));
    }

    @Override
    @Transactional
    public void notifyCommented(Issue issue, User actor) {
        Map<UUID, User> recipients = new LinkedHashMap<>();
        issue.getAssignees().forEach(user -> recipients.put(user.getId(), user));
        if (issue.getCreatedBy() != null) {
            recipients.put(issue.getCreatedBy().getId(), issue.getCreatedBy());
        }
        recipients.remove(actor.getId());

        String message = actor.getFullName() + " comentó en " + describe(issue);
        recipients.values().forEach(user -> save(user, actor, NotificationType.ISSUE_COMMENTED, message, issue));
    }

    private void save(User recipient, User actor, NotificationType type, String message, Issue issue) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setActor(actor);
        notification.setType(type);
        notification.setMessage(message);
        notification.setIssue(issue);
        notificationRepository.save(notification);
    }

    /** «WEB-12: Diseñar login», recortando títulos muy largos. */
    private static String describe(Issue issue) {
        String title = issue.getTitle();
        if (title.length() > MAX_TITLE_IN_MESSAGE) {
            title = title.substring(0, MAX_TITLE_IN_MESSAGE) + "…";
        }
        return issue.getCode() + ": " + title;
    }
}
