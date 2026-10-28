package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.CountResponse;
import com.taskflow.api.dto.response.NotificationCountsResponse;
import com.taskflow.api.dto.response.NotificationResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Comment;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Notification;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.NotificationType;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.NotificationRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final int MAX_TITLE_IN_MESSAGE = 200;

    private final NotificationRepository notificationRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final CollaborationMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getMyNotifications(UUID userId, boolean unreadOnly,
                                                                 Collection<NotificationType> types,
                                                                 Pageable pageable) {
        Set<NotificationType> filter = types == null || types.isEmpty()
                ? EnumSet.allOf(NotificationType.class)
                : EnumSet.copyOf(types);
        Page<Notification> page = notificationRepository.findMine(userId, filter, unreadOnly, pageable);
        return PageResponse.from(page, mapper::toNotificationResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public CountResponse countUnread(UUID userId) {
        return new CountResponse(notificationRepository.countByRecipientIdAndReadFalse(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationCountsResponse counts(UUID userId) {
        return new NotificationCountsResponse(
                notificationRepository.countByRecipientIdAndReadFalse(userId),
                notificationRepository.countByRecipientIdAndTypeAndReadFalse(userId, NotificationType.ISSUE_MENTIONED),
                notificationRepository.countByRecipientIdAndType(userId, NotificationType.ISSUE_MENTIONED));
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
                .forEach(user -> save(user, actor, NotificationType.ISSUE_ASSIGNED, message, issue, null));
    }

    @Override
    @Transactional
    public void notifyCommented(Issue issue, User actor, Comment comment, Collection<User> mentioned) {
        notifyMentioned(issue, actor, comment, mentioned);

        Map<UUID, User> recipients = new LinkedHashMap<>();
        issue.getAssignees().forEach(user -> recipients.put(user.getId(), user));
        if (issue.getCreatedBy() != null) {
            recipients.put(issue.getCreatedBy().getId(), issue.getCreatedBy());
        }
        recipients.remove(actor.getId());
        // La mención gana: quien fue mencionado no recibe además el aviso del comentario.
        mentioned.forEach(user -> recipients.remove(user.getId()));

        String message = actor.getFullName() + " comentó en " + describe(issue);
        recipients.values()
                .forEach(user -> save(user, actor, NotificationType.ISSUE_COMMENTED, message, issue, comment));
    }

    @Override
    @Transactional
    public void notifyMentioned(Issue issue, User actor, Comment comment, Collection<User> mentioned) {
        String message = actor.getFullName() + " te mencionó en " + describe(issue);
        distinctExcept(mentioned, actor)
                .forEach(user -> save(user, actor, NotificationType.ISSUE_MENTIONED, message, issue, comment));
    }

    @Override
    @Transactional
    public void notifyUnblocked(Issue blocked, Issue blocker, User actor) {
        String message = blocked.getCode() + " ya no está bloqueada: " + blocker.getCode() + " se completó";
        distinctExcept(blocked.getAssignees(), actor)
                .forEach(user -> save(user, actor, NotificationType.ISSUE_UNBLOCKED, message, blocked, null));
    }

    @Override
    @Transactional
    public void notifyRetroStarted(Cycle cycle, User actor) {
        String message = actor.getFullName() + " abrió la retrospectiva de «" + cycle.getName()
                + "»: agrega tus notas";
        Collection<User> members = projectMemberRepository.findAllByProjectIdWithUser(cycle.getProject().getId())
                .stream()
                .map(ProjectMember::getUser)
                .toList();
        distinctExcept(members, actor).forEach(user -> {
            Notification notification = notification(user, actor, NotificationType.RETRO_STARTED, message);
            notification.setCycle(cycle);
            notificationRepository.save(notification);
        });
    }

    /** Usuarios sin repetir (por id) y sin quien hizo la acción. */
    private static Collection<User> distinctExcept(Collection<User> users, User actor) {
        Map<UUID, User> byId = new LinkedHashMap<>();
        users.forEach(user -> byId.putIfAbsent(user.getId(), user));
        byId.remove(actor.getId());
        return byId.values();
    }

    private void save(User recipient, User actor, NotificationType type, String message, Issue issue,
                      Comment comment) {
        Notification notification = notification(recipient, actor, type, message);
        notification.setIssue(issue);
        notification.setComment(comment);
        notificationRepository.save(notification);
    }

    private static Notification notification(User recipient, User actor, NotificationType type, String message) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setActor(actor);
        notification.setType(type);
        notification.setMessage(message);
        return notification;
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
