package com.taskflow.api.repository;

import com.taskflow.api.entity.Notification;
import com.taskflow.api.entity.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Notificaciones del usuario de los tipos indicados (todas o solo las no leídas), con lo que muestra la bandeja
     * ya cargado: autor, tarea o ciclo con su proyecto y workspace, y comentario.
     */
    @EntityGraph(attributePaths = {"actor", "issue", "issue.project", "issue.project.workspace",
            "cycle", "cycle.project", "cycle.project.workspace", "comment"})
    @Query(value = """
            select n from Notification n
            where n.recipient.id = :recipientId and n.type in :types and (:unreadOnly = false or n.read = false)
            """,
            countQuery = """
            select count(n) from Notification n
            where n.recipient.id = :recipientId and n.type in :types and (:unreadOnly = false or n.read = false)
            """)
    Page<Notification> findMine(@Param("recipientId") UUID recipientId,
                                @Param("types") Collection<NotificationType> types,
                                @Param("unreadOnly") boolean unreadOnly,
                                Pageable pageable);

    @EntityGraph(attributePaths = {"actor", "issue", "issue.project", "issue.project.workspace",
            "cycle", "cycle.project", "cycle.project.workspace", "comment"})
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    long countByRecipientIdAndReadFalse(UUID recipientId);

    long countByRecipientIdAndType(UUID recipientId, NotificationType type);

    long countByRecipientIdAndTypeAndReadFalse(UUID recipientId, NotificationType type);

    @Modifying
    @Query("update Notification n set n.read = true where n.recipient.id = :recipientId and n.read = false")
    int markAllAsRead(@Param("recipientId") UUID recipientId);
}
