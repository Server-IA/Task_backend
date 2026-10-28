package com.taskflow.api.repository;

import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.enums.StateGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CycleRepository extends JpaRepository<Cycle, UUID> {

    @Query("""
            select c from Cycle c
            where c.project.id = :projectId
            order by case when c.startDate is null then 1 else 0 end, c.startDate desc, c.createdAt desc
            """)
    List<Cycle> findAllByProjectId(@Param("projectId") UUID projectId);

    Optional<Cycle> findByIdAndProjectId(UUID id, UUID projectId);

    /** Avance de los ciclos indicados; un ciclo sin issues no aparece en el resultado. */
    @Query("""
            select c.id as id, count(i) as total,
                   coalesce(sum(case when i.state.stateGroup = :completed then 1 else 0 end), 0) as completed
            from Cycle c join c.issues i
            where c.id in :ids
            group by c.id
            """)
    List<IssueProgress> findProgress(@Param("ids") Collection<UUID> ids,
                                     @Param("completed") StateGroup completed);

    @Query("select i.id from Cycle c join c.issues i where c.id = :cycleId")
    List<UUID> findIssueIds(@Param("cycleId") UUID cycleId);

    /** Saca los issues de cualquier otro ciclo, porque un issue solo puede estar en uno. */
    @Modifying
    @Query(value = "DELETE FROM cycle_issues WHERE issue_id IN (:issueIds) AND cycle_id <> :cycleId",
            nativeQuery = true)
    int removeIssuesFromOtherCycles(@Param("cycleId") UUID cycleId, @Param("issueIds") Collection<UUID> issueIds);
}
