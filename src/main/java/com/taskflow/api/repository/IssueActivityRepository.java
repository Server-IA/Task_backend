package com.taskflow.api.repository;

import com.taskflow.api.entity.IssueActivity;
import com.taskflow.api.entity.enums.ActivityField;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueActivityRepository extends JpaRepository<IssueActivity, UUID> {

    @Query("select a from IssueActivity a left join fetch a.actor where a.issue.id = :issueId order by a.createdAt asc")
    List<IssueActivity> findAllByIssueIdWithActor(@Param("issueId") UUID issueId);

    /** Última entrada del historial de la tarea (la más reciente por fecha de creación). */
    Optional<IssueActivity> findFirstByIssueIdOrderByCreatedAtDescIdDesc(UUID issueId);

    /**
     * Feed de actividad de varios proyectos, de lo más reciente a lo más antiguo, con la tarea, su proyecto y
     * el autor ya cargados. {@code actorId} es opcional ({@code null} = cualquiera).
     */
    @Query(value = """
            select a from IssueActivity a
            join fetch a.issue i
            join fetch i.project p
            left join fetch a.actor
            where p.id in :projectIds
              and a.field in :fields
              and (:actorId is null or a.actor.id = :actorId)
            order by a.createdAt desc, a.id desc
            """,
            countQuery = """
            select count(a) from IssueActivity a
            where a.issue.project.id in :projectIds
              and a.field in :fields
              and (:actorId is null or a.actor.id = :actorId)
            """)
    Page<IssueActivity> findFeed(@Param("projectIds") Collection<UUID> projectIds,
                                 @Param("fields") Collection<ActivityField> fields,
                                 @Param("actorId") UUID actorId,
                                 Pageable pageable);

    /** Como {@link #findFeed}, por cursor: lo que va después de ({@code beforeAt}, {@code beforeId}), sin contar. */
    @Query("""
            select a from IssueActivity a
            join fetch a.issue i
            join fetch i.project p
            left join fetch a.actor
            where p.id in :projectIds
              and a.field in :fields
              and (:actorId is null or a.actor.id = :actorId)
              and (a.createdAt < :beforeAt or (a.createdAt = :beforeAt and a.id < :beforeId))
            order by a.createdAt desc, a.id desc
            """)
    List<IssueActivity> findFeedBefore(@Param("projectIds") Collection<UUID> projectIds,
                                       @Param("fields") Collection<ActivityField> fields,
                                       @Param("actorId") UUID actorId,
                                       @Param("beforeAt") Instant beforeAt,
                                       @Param("beforeId") UUID beforeId,
                                       Limit limit);

    /**
     * Historial del proyecto en {@code [from, to)} con autor (las entradas sin autor no se incluyen), de la más
     * antigua a la más reciente, con la tarea, su estado y su proyecto cargados.
     */
    @Query("""
            select a from IssueActivity a
            join fetch a.actor
            join fetch a.issue i
            join fetch i.state
            join fetch i.project
            where i.project.id = :projectId and a.createdAt >= :from and a.createdAt < :to
            order by a.createdAt asc, a.id asc
            """)
    List<IssueActivity> findAllInProjectBetween(@Param("projectId") UUID projectId, @Param("from") Instant from,
                                                @Param("to") Instant to);

    /** Entradas del historial por proyecto desde la fecha indicada, de más a menos. */
    @Query("""
            select new com.taskflow.api.repository.ProjectActivityCount(p.id, p.name, p.identifier, p.color, count(a))
            from IssueActivity a join a.issue i join i.project p
            where p.id in :projectIds and a.createdAt >= :since
            group by p.id, p.name, p.identifier, p.color
            order by count(a) desc, p.name asc
            """)
    List<ProjectActivityCount> countByProjectSince(@Param("projectIds") Collection<UUID> projectIds,
                                                   @Param("since") Instant since);

    /**
     * Momento de la última entrada del historial del proyecto en {@code [from, to)} (de cualquier tipo, comentarios
     * incluidos); {@code null} si no hubo ninguna.
     */
    @Query("""
            select max(a.createdAt) from IssueActivity a
            where a.issue.project.id = :projectId and a.createdAt >= :from and a.createdAt < :to
            """)
    Instant findLastInProjectBetween(@Param("projectId") UUID projectId, @Param("from") Instant from,
                                     @Param("to") Instant to);

    @Query("""
            select count(a) from IssueActivity a
            where a.issue.project.id in :projectIds and a.field = :field and a.createdAt >= :since
            """)
    long countByFieldSince(@Param("projectIds") Collection<UUID> projectIds, @Param("field") ActivityField field,
                           @Param("since") Instant since);
}
