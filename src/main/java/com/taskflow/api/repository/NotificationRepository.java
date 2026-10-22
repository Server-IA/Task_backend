package com.taskflow.api.repository;

import com.taskflow.api.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @EntityGraph(attributePaths = {"actor", "issue", "issue.project", "issue.project.workspace"})
    Page<Notification> findAllByRecipientId(UUID recipientId, Pageable pageable);

    @EntityGraph(attributePaths = {"actor", "issue", "issue.project", "issue.project.workspace"})
    Page<Notification> findAllByRecipientIdAndReadFalse(UUID recipientId, Pageable pageable);

    @EntityGraph(attributePaths = {"actor", "issue", "issue.project", "issue.project.workspace"})
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    long countByRecipientIdAndReadFalse(UUID recipientId);

    @Modifying
    @Query("update Notification n set n.read = true where n.recipient.id = :recipientId and n.read = false")
    int markAllAsRead(@Param("recipientId") UUID recipientId);
}
