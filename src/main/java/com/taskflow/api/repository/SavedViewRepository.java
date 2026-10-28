package com.taskflow.api.repository;

import com.taskflow.api.entity.SavedView;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedViewRepository extends JpaRepository<SavedView, UUID> {

    /** Las compartidas del proyecto y las personales del usuario: primero las compartidas, cada grupo en su orden. */
    @Query("""
            select v from SavedView v
            join fetch v.owner
            where v.project.id = :projectId and (v.shared = true or v.owner.id = :userId)
            order by v.shared desc, v.position asc, v.createdAt asc
            """)
    List<SavedView> findVisible(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    @EntityGraph(attributePaths = "owner")
    Optional<SavedView> findByIdAndProjectId(UUID id, UUID projectId);

    /** Grupo de las vistas compartidas del proyecto, en orden. */
    List<SavedView> findAllByProjectIdAndSharedTrueOrderByPositionAscCreatedAtAsc(UUID projectId);

    /** Grupo de las vistas personales de un usuario en el proyecto, en orden. */
    List<SavedView> findAllByProjectIdAndOwnerIdAndSharedFalseOrderByPositionAscCreatedAtAsc(UUID projectId,
                                                                                              UUID ownerId);
}
