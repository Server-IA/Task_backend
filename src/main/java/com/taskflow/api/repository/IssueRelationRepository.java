package com.taskflow.api.repository;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.enums.IssueRelationType;
import com.taskflow.api.entity.enums.StateGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRelationRepository extends JpaRepository<IssueRelation, UUID> {

    /**
     * Relaciones en las que participa la tarea (en cualquier lado), con ambas tareas, sus estados y sus proyectos
     * cargados (la otra tarea puede ser de otro proyecto del workspace).
     */
    @Query("""
            select r from IssueRelation r
            join fetch r.source s join fetch s.state join fetch s.project
            join fetch r.target t join fetch t.state join fetch t.project
            where s.id = :issueId or t.id = :issueId
            """)
    List<IssueRelation> findAllByIssueId(@Param("issueId") UUID issueId);

    /** La relación solo si la tarea participa en ella. */
    @Query("""
            select r from IssueRelation r
            join fetch r.source s join fetch r.target t
            where r.id = :id and (s.id = :issueId or t.id = :issueId)
            """)
    Optional<IssueRelation> findByIdAndIssueId(@Param("id") UUID id, @Param("issueId") UUID issueId);

    boolean existsBySourceIdAndTargetIdAndType(UUID sourceId, UUID targetId, IssueRelationType type);

    /**
     * Relaciones {@code RELATES} de las tareas indicadas (en cualquier lado), con ambas tareas y sus proyectos
     * cargados: los chips «relacionada: WEB-52» de «Historias».
     */
    @Query("""
            select r from IssueRelation r
            join fetch r.source s join fetch s.project
            join fetch r.target t join fetch t.project
            where r.type = com.taskflow.api.entity.enums.IssueRelationType.RELATES
              and (s.id in :issueIds or t.id in :issueIds)
            """)
    List<IssueRelation> findRelatesOf(@Param("issueIds") Collection<UUID> issueIds);

    /** Tareas que bloquea la indicada, con su estado y su proyecto cargados. */
    @Query("""
            select i from Issue i join fetch i.state join fetch i.project
            where i.id in (select r.target.id from IssueRelation r
                           where r.source.id = :blockerId
                             and r.type = com.taskflow.api.entity.enums.IssueRelationType.BLOCKS)
            """)
    List<Issue> findIssuesBlockedBy(@Param("blockerId") UUID blockerId);

    /**
     * Bloqueos abiertos (tarea origen fuera de {@code closedGroups}) de las tareas indicadas, con ambas tareas
     * cargadas (la que bloquea, con su estado y su proyecto).
     */
    @Query("""
            select r from IssueRelation r
            join fetch r.source s join fetch s.project join fetch s.state
            join fetch r.target
            where r.type = com.taskflow.api.entity.enums.IssueRelationType.BLOCKS
              and r.target.id in :issueIds
              and s.state.stateGroup not in :closedGroups
            """)
    List<IssueRelation> findOpenBlockersOf(@Param("issueIds") Collection<UUID> issueIds,
                                           @Param("closedGroups") Collection<StateGroup> closedGroups);

    /**
     * Un estado acaba de pasar al grupo COMPLETED: los bloqueos cuya tarea origen está en él y cuya tarea bloqueada,
     * abierta, ya no tiene ningún otro bloqueo abierto (con las tareas bloqueadas, su estado y su proyecto cargados).
     * Una tarea bloqueada por varias del estado sale una vez por cada una; quien llama se queda con una.
     */
    @Query("""
            select r from IssueRelation r
            join fetch r.source s join fetch s.project
            join fetch r.target t join fetch t.state join fetch t.project
            where r.type = com.taskflow.api.entity.enums.IssueRelationType.BLOCKS
              and s.state.id = :stateId
              and t.state.stateGroup not in :closedGroups
              and not exists (select 1 from IssueRelation o
                              where o.target = t
                                and o.type = com.taskflow.api.entity.enums.IssueRelationType.BLOCKS
                                and o.source.state.stateGroup not in :closedGroups)
            order by t.id, s.sequenceId
            """)
    List<IssueRelation> findUnblockedByState(@Param("stateId") UUID stateId,
                                             @Param("closedGroups") Collection<StateGroup> closedGroups);

    /** Bloqueos de la tarea que siguen abiertos (estado fuera de {@code closedGroups}), sin contar uno de ellos. */
    @Query("""
            select count(r) from IssueRelation r
            where r.target.id = :issueId
              and r.type = com.taskflow.api.entity.enums.IssueRelationType.BLOCKS
              and r.source.id <> :excludedBlockerId
              and r.source.state.stateGroup not in :closedGroups
            """)
    long countOpenBlockersExcept(@Param("issueId") UUID issueId, @Param("excludedBlockerId") UUID excludedBlockerId,
                                 @Param("closedGroups") Collection<StateGroup> closedGroups);
}
