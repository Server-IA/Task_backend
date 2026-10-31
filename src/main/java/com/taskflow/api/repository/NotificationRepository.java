package com.taskflow.api.repository;

import com.taskflow.api.entity.Notification;
import com.taskflow.api.entity.enums.NotificationType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Solo las de proyectos que el destinatario puede ver todavía (miembro del proyecto o project manager del
     * workspace): las de un proyecto o workspace del que lo quitaron no se muestran ni cuentan.
     */
    String ACCESSIBLE = """
            (n.issue is null or exists (select 1 from Issue i where i = n.issue and (
                exists (select 1 from ProjectMember pm where pm.project = i.project and pm.user.id = :recipientId)
                or exists (select 1 from WorkspaceMember wm where wm.workspace = i.project.workspace
                           and wm.user.id = :recipientId
                           and wm.role = com.taskflow.api.entity.enums.WorkspaceRole.OWNER))))
            and (n.cycle is null or exists (select 1 from Cycle c where c = n.cycle and (
                exists (select 1 from ProjectMember pm where pm.project = c.project and pm.user.id = :recipientId)
                or exists (select 1 from WorkspaceMember wm where wm.workspace = c.project.workspace
                           and wm.user.id = :recipientId
                           and wm.role = com.taskflow.api.entity.enums.WorkspaceRole.OWNER))))
            and (n.workspace is null or exists (select 1 from WorkspaceMember wm where wm.workspace = n.workspace
                           and wm.user.id = :recipientId
                           and wm.role = com.taskflow.api.entity.enums.WorkspaceRole.OWNER))
            """;

    /**
     * Notificaciones del usuario de los tipos indicados (todas o solo las no leídas), con lo que muestra la bandeja
     * ya cargado: autor, tarea o ciclo con su proyecto y workspace, y comentario. Sin las de proyectos que ya no
     * puede ver ({@link #ACCESSIBLE}).
     */
    @EntityGraph(attributePaths = {"actor", "issue", "issue.project", "issue.project.workspace",
            "cycle", "cycle.project", "cycle.project.workspace", "comment", "workspace"})
    @Query(value = """
            select n from Notification n
            where n.recipient.id = :recipientId and n.type in :types and (:unreadOnly = false or n.read = false)
              and """ + ACCESSIBLE,
            countQuery = """
            select count(n) from Notification n
            where n.recipient.id = :recipientId and n.type in :types and (:unreadOnly = false or n.read = false)
              and """ + ACCESSIBLE)
    Page<Notification> findMine(@Param("recipientId") UUID recipientId,
                                @Param("types") Collection<NotificationType> types,
                                @Param("unreadOnly") boolean unreadOnly,
                                Pageable pageable);

    /**
     * Como {@link #findMine}, pero por cursor: las que van después de ({@code beforeAt}, {@code beforeId}) en el orden
     * de la bandeja (lo más nuevo primero y, a igual fecha, por id descendente), sin contar el total.
     */
    @EntityGraph(attributePaths = {"actor", "issue", "issue.project", "issue.project.workspace",
            "cycle", "cycle.project", "cycle.project.workspace", "comment", "workspace"})
    @Query("""
            select n from Notification n
            where n.recipient.id = :recipientId and n.type in :types and (:unreadOnly = false or n.read = false)
              and (n.createdAt < :beforeAt or (n.createdAt = :beforeAt and n.id < :beforeId))
              and """ + ACCESSIBLE + """
            order by n.createdAt desc, n.id desc
            """)
    List<Notification> findMineBefore(@Param("recipientId") UUID recipientId,
                                      @Param("types") Collection<NotificationType> types,
                                      @Param("unreadOnly") boolean unreadOnly,
                                      @Param("beforeAt") Instant beforeAt,
                                      @Param("beforeId") UUID beforeId,
                                      Limit limit);

    /** Cuántas hay de los tipos indicados (todas o solo las no leídas), con el mismo criterio que {@link #findMine}. */
    @Query("""
            select count(n) from Notification n
            where n.recipient.id = :recipientId and n.type in :types and (:unreadOnly = false or n.read = false)
              and """ + ACCESSIBLE)
    long countMine(@Param("recipientId") UUID recipientId,
                   @Param("types") Collection<NotificationType> types,
                   @Param("unreadOnly") boolean unreadOnly);

    @EntityGraph(attributePaths = {"actor", "issue", "issue.project", "issue.project.workspace",
            "cycle", "cycle.project", "cycle.project.workspace", "comment", "workspace"})
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    /** Si ya se avisó a alguien de que el workspace está por llenarse después de {@code after}. */
    boolean existsByWorkspaceIdAndTypeAndCreatedAtAfter(UUID workspaceId, NotificationType type, Instant after);

    /** Limpieza programada: borra las leídas creadas antes de {@code before}. */
    @Modifying
    @Query("delete from Notification n where n.read = true and n.createdAt < :before")
    int deleteReadBefore(@Param("before") Instant before);

    @Modifying
    @Query("update Notification n set n.read = true where n.recipient.id = :recipientId and n.read = false")
    int markAllAsRead(@Param("recipientId") UUID recipientId);
}
