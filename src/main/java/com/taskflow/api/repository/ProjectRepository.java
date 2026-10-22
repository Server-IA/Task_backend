package com.taskflow.api.repository;

import com.taskflow.api.entity.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** Busca el proyecto solo si pertenece al workspace indicado. */
    @Query("select p from Project p join fetch p.workspace w where p.id = :id and w.slug = :slug")
    Optional<Project> findInWorkspace(@Param("id") UUID id, @Param("slug") String slug);

    List<Project> findAllByWorkspaceIdOrderByName(UUID workspaceId);

    @Query("""
            select p from Project p
            where p.workspace.id = :workspaceId
              and exists (select 1 from ProjectMember pm where pm.project = p and pm.user.id = :userId)
            order by p.name
            """)
    List<Project> findAllByWorkspaceIdAndMember(@Param("workspaceId") UUID workspaceId,
                                                @Param("userId") UUID userId);

    boolean existsByWorkspaceIdAndIdentifier(UUID workspaceId, String identifier);

    /**
     * Bloquea la fila del proyecto hasta que termine la transacción. Se usa al crear un issue para
     * incrementar {@code issue_counter} sin que dos peticiones obtengan el mismo número.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") UUID id);
}
