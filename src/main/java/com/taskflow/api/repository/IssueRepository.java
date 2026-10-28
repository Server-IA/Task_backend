package com.taskflow.api.repository;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.enums.StateGroup;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID>, JpaSpecificationExecutor<Issue> {

    @EntityGraph(attributePaths = {"project", "state"})
    Optional<Issue> findByIdAndProjectId(UUID id, UUID projectId);

    @EntityGraph(attributePaths = {"project", "state"})
    List<Issue> findAllByParentIdOrderBySequenceIdAsc(UUID parentId);

    /** Issues de una columna del Kanban en orden; se usa para renumerar {@code sort_order}. */
    List<Issue> findAllByStateIdOrderBySortOrderAsc(UUID stateId);

    boolean existsByStateId(UUID stateId);

    List<Issue> findAllByProjectIdAndIdIn(UUID projectId, Collection<UUID> ids);

    /** Mayor {@code sort_order} de la columna, o {@code null} si está vacía. */
    @Query("select max(i.sortOrder) from Issue i where i.state.id = :stateId")
    Double findMaxSortOrder(@Param("stateId") UUID stateId);

    /** El estado pasó al grupo COMPLETED: sus tareas quedan completadas desde ahora. */
    @Modifying
    @Query("update Issue i set i.completedAt = :now where i.state.id = :stateId and i.completedAt is null")
    int markCompletedInState(@Param("stateId") UUID stateId, @Param("now") Instant now);

    /** El estado salió del grupo COMPLETED: sus tareas dejan de estar completadas. */
    @Modifying
    @Query("update Issue i set i.completedAt = null where i.state.id = :stateId")
    int clearCompletedInState(@Param("stateId") UUID stateId);

    // ----- Estadísticas del proyecto -----

    @Query("""
            select new com.taskflow.api.repository.IssueStatsRow(i.id, i.sequenceId, i.title, s.id, s.stateGroup,
                   i.dueDate, i.createdAt, i.updatedAt, i.completedAt)
            from Issue i join i.state s
            where i.project.id = :projectId
            """)
    List<IssueStatsRow> findStatsRows(@Param("projectId") UUID projectId);

    /** Asignados de las tareas que no están en los grupos excluidos (una fila por tarea y usuario). */
    @Query("""
            select new com.taskflow.api.repository.IssueAssigneeRow(i.id, u)
            from Issue i join i.assignees u
            where i.project.id = :projectId and i.state.stateGroup not in :excludedGroups
            """)
    List<IssueAssigneeRow> findAssignees(@Param("projectId") UUID projectId,
                                         @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    // ----- Mi trabajo (excludedGroups = grupos cerrados, para quedarse con las tareas abiertas) -----

    @EntityGraph(attributePaths = {"project", "state"})
    @Query("""
            select i from Issue i
            where i.project.id in :projectIds and i.state.stateGroup not in :excludedGroups
              and i.id in (select x.id from Issue x join x.assignees u where u.id = :userId)
            """)
    List<Issue> findAssignedTo(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                               @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    @Query("""
            select count(i) from Issue i
            where i.project.id in :projectIds and i.state.stateGroup not in :excludedGroups
              and i.id in (select x.id from Issue x join x.assignees u where u.id = :userId)
            """)
    long countAssignedTo(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                         @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    @EntityGraph(attributePaths = {"project", "state"})
    @Query("""
            select i from Issue i
            where i.project.id in :projectIds and i.state.stateGroup not in :excludedGroups
              and i.createdBy.id = :userId
            """)
    List<Issue> findCreatedBy(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                              @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    @Query("""
            select count(i) from Issue i
            where i.project.id in :projectIds and i.state.stateGroup not in :excludedGroups
              and i.createdBy.id = :userId
            """)
    long countCreatedBy(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                        @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    @EntityGraph(attributePaths = {"project", "state"})
    @Query("""
            select i from Issue i
            where i.project.id in :projectIds and i.state.stateGroup not in :excludedGroups
              and i.id in (select c.issue.id from Comment c where c.author.id = :userId)
            """)
    List<Issue> findCommentedBy(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                                @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    @Query("""
            select count(i) from Issue i
            where i.project.id in :projectIds and i.state.stateGroup not in :excludedGroups
              and i.id in (select c.issue.id from Comment c where c.author.id = :userId)
            """)
    long countCommentedBy(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                          @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    /** Tareas asignadas al usuario cuyo estado está en el grupo indicado (por ejemplo, STARTED). */
    @Query("""
            select count(i) from Issue i
            where i.project.id in :projectIds and i.state.stateGroup = :group
              and i.id in (select x.id from Issue x join x.assignees u where u.id = :userId)
            """)
    long countAssignedInGroup(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                              @Param("group") StateGroup group);

    @Query("""
            select count(i) from Issue i
            where i.project.id in :projectIds and i.completedAt >= :since
              and i.id in (select x.id from Issue x join x.assignees u where u.id = :userId)
            """)
    long countAssignedCompletedSince(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                                     @Param("since") Instant since);

    // ----- Daily -----

    /** Tareas del proyecto que no están en los grupos excluidos y tienen algún asignado, con sus asignados. */
    @EntityGraph(attributePaths = {"project", "state", "assignees"})
    @Query("""
            select i from Issue i
            where i.project.id = :projectId and i.state.stateGroup not in :excludedGroups
              and i.assignees is not empty
            """)
    List<Issue> findAssignedInProject(@Param("projectId") UUID projectId,
                                      @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    // ----- Resumen de actividad -----

    @Query("select count(i) from Issue i where i.project.id in :projectIds and i.completedAt >= :since")
    long countCompletedSince(@Param("projectIds") Collection<UUID> projectIds, @Param("since") Instant since);

    @Query("select count(i) from Issue i where i.project.id in :projectIds and i.createdAt >= :since")
    long countCreatedSince(@Param("projectIds") Collection<UUID> projectIds, @Param("since") Instant since);
}
