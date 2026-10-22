package com.taskflow.api.repository;

import com.taskflow.api.entity.Issue;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Mayor {@code sort_order} de la columna, o {@code null} si está vacía. */
    @Query("select max(i.sortOrder) from Issue i where i.state.id = :stateId")
    Double findMaxSortOrder(@Param("stateId") UUID stateId);
}
