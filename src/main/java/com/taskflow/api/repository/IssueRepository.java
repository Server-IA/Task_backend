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

    /**
     * Bloquea la fila de la tarea hasta el final de la transacción ({@code SELECT … FOR NO KEY UPDATE}). Se llama
     * antes de leer la tarea: dos cambios de la misma tarea a la vez se ejecutan en fila y el segundo ve lo que dejó el
     * primero (la descripción, su versión, el estado de una historia…).
     * <p>
     * {@code NO KEY UPDATE} y no {@code UPDATE}: bloquea a los demás que escriben la fila, pero no choca con el
     * {@code FOR KEY SHARE} que toma PostgreSQL al guardar una hija que apunta a ella (la clave foránea
     * {@code parent_id}). Así, crear o mover tareas de una historia bloqueada no espera, y una tarea que cambia de
     * historia no se cruza con quien bloquea esa historia.
     */
    @Query(value = "SELECT 1 FROM issues WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    List<Integer> lockById(@Param("id") UUID id);

    /**
     * Como {@link #lockById}, varias filas y siempre en el mismo orden (el de los ids en PostgreSQL): dos transacciones
     * que bloquean las mismas tareas no se esperan mutuamente. El orden lo pone la base de datos: el de
     * {@link UUID#compareTo} es distinto.
     */
    @Query(value = "SELECT 1 FROM issues WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE", nativeQuery = true)
    List<Integer> lockAllById(@Param("ids") Collection<UUID> ids);

    /**
     * Bloquea las hijas de la tarea (en el orden de {@link #lockAllById}). Antes de eliminar una historia: su borrado
     * les quita el padre ({@code ON DELETE SET NULL}), y así se bloquean las hijas antes que la historia, en el mismo
     * orden que quien mueve una de ellas (primero la tarea y luego su historia).
     */
    @Query(value = "SELECT 1 FROM issues WHERE parent_id = :parentId ORDER BY id FOR NO KEY UPDATE",
            nativeQuery = true)
    List<Integer> lockChildrenOf(@Param("parentId") UUID parentId);

    /** Historias con su estado y su proyecto (para ponerlas al día en bloque). */
    @EntityGraph(attributePaths = {"project", "state"})
    @Query("select i from Issue i where i.id in :ids")
    List<Issue> findAllWithStateByIdIn(@Param("ids") Collection<UUID> ids);

    /** Tarea de cualquier proyecto del workspace (para las dependencias entre proyectos). */
    @EntityGraph(attributePaths = {"project", "state"})
    Optional<Issue> findByIdAndProjectWorkspaceId(UUID id, UUID workspaceId);

    @EntityGraph(attributePaths = {"project", "state"})
    List<Issue> findAllByParentIdOrderBySequenceIdAsc(UUID parentId);

    boolean existsByParentId(UUID parentId);

    // ----- Historias -----

    /**
     * Hijas de la historia contadas por tipo y grupo de estado (una fila por combinación). Con esto se decide si está
     * «lista para revisar» ({@code StoryRules.isReady}).
     */
    @Query("""
            select new com.taskflow.api.repository.StoryChildCount(i.type, s.stateGroup, count(i))
            from Issue i join i.state s
            where i.parent.id = :storyId
            group by i.type, s.stateGroup
            """)
    List<StoryChildCount> countChildrenOf(@Param("storyId") UUID storyId);

    /** {@link #countChildrenOf} de varias historias en una consulta (una fila por historia, tipo y grupo). */
    @Query("""
            select new com.taskflow.api.repository.StoryChildCountRow(i.parent.id, i.type, s.stateGroup, count(i))
            from Issue i join i.state s
            where i.parent.id in :storyIds
            group by i.parent.id, i.type, s.stateGroup
            """)
    List<StoryChildCountRow> countChildrenOfAll(@Param("storyIds") Collection<UUID> storyIds);

    /**
     * Tareas ({@code TASK}) del estado que tienen padre, con su proyecto, por número: la que «empezó» cada historia
     * cuando el estado pasa al grupo STARTED.
     */
    @EntityGraph(attributePaths = {"project"})
    @Query("""
            select i from Issue i
            where i.state.id = :stateId and i.parent is not null
              and i.type = com.taskflow.api.entity.enums.IssueType.TASK
            order by i.sequenceId asc
            """)
    List<Issue> findTasksWithParentInState(@Param("stateId") UUID stateId);

    /** Historias del proyecto, de la más nueva a la más vieja. */
    @EntityGraph(attributePaths = {"project", "state", "closedBy"})
    @Query("""
            select i from Issue i
            where i.project.id = :projectId and i.type = com.taskflow.api.entity.enums.IssueType.STORY
            order by i.sequenceId desc
            """)
    List<Issue> findStories(@Param("projectId") UUID projectId);

    /** Tareas y bugs de las historias indicadas, con su estado y sus asignados (las filas desplegadas). */
    @EntityGraph(attributePaths = {"project", "state", "assignees", "parent"})
    @Query("select i from Issue i where i.parent.id in :storyIds order by i.sequenceId asc")
    List<Issue> findChildrenOf(@Param("storyIds") Collection<UUID> storyIds);

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

    /**
     * El estado pasó de un grupo abierto a uno cerrado (COMPLETED o CANCELLED): sus historias quedan cerradas desde
     * ahora, por quien cambió el grupo.
     */
    @Modifying
    @Query("""
            update Issue i set i.closedAt = :now, i.closedBy = :actor
            where i.state.id = :stateId and i.type = com.taskflow.api.entity.enums.IssueType.STORY
              and i.closedAt is null
            """)
    int closeStoriesInState(@Param("stateId") UUID stateId, @Param("now") Instant now,
                            @Param("actor") com.taskflow.api.entity.User actor);

    /** El estado pasó de un grupo cerrado a uno abierto: sus historias se reabren. */
    @Modifying
    @Query("""
            update Issue i set i.closedAt = null, i.closedBy = null
            where i.state.id = :stateId and i.type = com.taskflow.api.entity.enums.IssueType.STORY
            """)
    int reopenStoriesInState(@Param("stateId") UUID stateId);

    /**
     * Ids de las historias a poner al día después de que un estado cambió de grupo: las que están en ese estado y las
     * madres de las tareas y bugs que están en él.
     */
    @Query("""
            select i.id from Issue i
            where i.type = com.taskflow.api.entity.enums.IssueType.STORY
              and (i.state.id = :stateId
                   or i.id in (select c.parent.id from Issue c where c.state.id = :stateId and c.parent is not null))
            """)
    List<UUID> findStoryIdsAffectedByState(@Param("stateId") UUID stateId);

    /** Issues de ese estado (para avisar a las tareas que dejaron de estar bloqueadas). */
    @EntityGraph(attributePaths = {"project", "state"})
    List<Issue> findAllByStateId(UUID stateId);

    /** Al quitar a alguien de un proyecto, deja de estar asignado a sus tareas. */
    @Modifying
    @Query(value = """
            DELETE FROM issue_assignees ia
            USING issues i
            WHERE ia.issue_id = i.id AND i.project_id = :projectId AND ia.user_id = :userId
            """, nativeQuery = true)
    int removeAssigneeInProject(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    /** Al quitar a alguien de un workspace, deja de estar asignado a las tareas de todos sus proyectos. */
    @Modifying
    @Query(value = """
            DELETE FROM issue_assignees ia
            USING issues i, projects p
            WHERE ia.issue_id = i.id AND i.project_id = p.id AND p.workspace_id = :workspaceId
              AND ia.user_id = :userId
            """, nativeQuery = true)
    int removeAssigneeInWorkspace(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);

    /** El estado pasó al grupo STARTED: sus tareas que nunca habían empezado empiezan ahora. */
    @Modifying
    @Query("update Issue i set i.startedAt = :now where i.state.id = :stateId and i.startedAt is null")
    int markStartedInState(@Param("stateId") UUID stateId, @Param("now") Instant now);

    /** Cambió la checklist de la tarea. No pasa por la entidad, así que no modifica {@code updatedAt}. */
    @Modifying
    @Query("update Issue i set i.checklistUpdatedAt = :now where i.id = :issueId")
    int touchChecklist(@Param("issueId") UUID issueId, @Param("now") Instant now);

    // ----- Estadísticas del proyecto (cuentan el trabajo: tareas y bugs, no las historias) -----

    @Query("""
            select new com.taskflow.api.repository.IssueStatsRow(i.id, i.sequenceId, i.title, s.id, s.stateGroup,
                   i.dueDate, i.createdAt, i.updatedAt, i.startedAt, i.completedAt, i.type)
            from Issue i join i.state s
            where i.project.id = :projectId and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
            """)
    List<IssueStatsRow> findStatsRows(@Param("projectId") UUID projectId);

    /** Ids de las tareas del proyecto asignadas a alguno de los usuarios (el filtro por persona de las estadísticas). */
    @Query("""
            select distinct i.id from Issue i join i.assignees u
            where i.project.id = :projectId and u.id in :userIds and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
            """)
    List<UUID> findIdsAssignedToAny(@Param("projectId") UUID projectId, @Param("userIds") Collection<UUID> userIds);

    /** Asignados de las tareas que no están en los grupos excluidos (una fila por tarea y usuario). */
    @Query("""
            select new com.taskflow.api.repository.IssueAssigneeRow(i.id, u)
            from Issue i join i.assignees u
            where i.project.id = :projectId and i.state.stateGroup not in :excludedGroups and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
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

    @Query("""
            select count(i) from Issue i
            where i.project.id in :projectIds and i.completedAt >= :since
              and i.id in (select x.id from Issue x join x.assignees u where u.id = :userId)
            """)
    long countAssignedCompletedSince(@Param("projectIds") Collection<UUID> projectIds, @Param("userId") UUID userId,
                                     @Param("since") Instant since);

    // ----- Daily (el trabajo de cada persona: tareas y bugs, no las historias, que avanzan solas) -----

    /** Tareas del proyecto que no están en los grupos excluidos y tienen algún asignado, con sus asignados. */
    @EntityGraph(attributePaths = {"project", "state", "assignees"})
    @Query("""
            select i from Issue i
            where i.project.id = :projectId and i.state.stateGroup not in :excludedGroups
              and i.assignees is not empty and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
            """)
    List<Issue> findAssignedInProject(@Param("projectId") UUID projectId,
                                      @Param("excludedGroups") Collection<StateGroup> excludedGroups);

    /** Tareas de un ciclo con su estado y sus asignados. */
    @EntityGraph(attributePaths = {"state", "assignees"})
    @Query("select i from Issue i where i.id in (select ci.id from Cycle c join c.issues ci where c.id = :cycleId) "
            + "and i.type <> com.taskflow.api.entity.enums.IssueType.STORY")
    List<Issue> findAllInCycle(@Param("cycleId") UUID cycleId);

    /** Tareas del proyecto que empezaron (entraron por primera vez a un estado STARTED) en {@code [from, to)}. */
    @Query("""
            select count(i) from Issue i
            where i.project.id = :projectId and i.startedAt >= :from and i.startedAt < :to and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
            """)
    long countStartedBetween(@Param("projectId") UUID projectId, @Param("from") Instant from,
                             @Param("to") Instant to);

    /** Tareas del proyecto completadas en {@code [from, to)} (y que siguen completadas). */
    @Query("""
            select count(i) from Issue i
            where i.project.id = :projectId and i.completedAt >= :from and i.completedAt < :to and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
            """)
    long countCompletedBetween(@Param("projectId") UUID projectId, @Param("from") Instant from,
                               @Param("to") Instant to);

    // ----- Resumen de actividad (el trabajo: tareas y bugs, como las estadísticas; las historias no) -----

    @Query("""
            select count(i) from Issue i
            where i.project.id in :projectIds and i.completedAt >= :since
              and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
            """)
    long countCompletedSince(@Param("projectIds") Collection<UUID> projectIds, @Param("since") Instant since);

    @Query("""
            select count(i) from Issue i
            where i.project.id in :projectIds and i.createdAt >= :since
              and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
            """)
    long countCreatedSince(@Param("projectIds") Collection<UUID> projectIds, @Param("since") Instant since);
}
