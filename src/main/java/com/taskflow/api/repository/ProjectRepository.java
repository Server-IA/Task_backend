package com.taskflow.api.repository;

import com.taskflow.api.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("select p.id from Project p where p.workspace.id = :workspaceId")
    List<UUID> findIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    @Query("""
            select pm.project.id from ProjectMember pm
            where pm.user.id = :userId and pm.project.workspace.id = :workspaceId
            """)
    List<UUID> findIdsByWorkspaceIdAndMember(@Param("workspaceId") UUID workspaceId,
                                             @Param("userId") UUID userId);

    boolean existsByWorkspaceIdAndIdentifier(UUID workspaceId, String identifier);

    /**
     * Incrementa el contador de issues con un UPDATE atómico. La fila queda bloqueada hasta el final de la
     * transacción, así dos issues creados a la vez nunca reciben el mismo número.
     */
    @Modifying
    @Query(value = "UPDATE projects SET issue_counter = issue_counter + 1 WHERE id = :id", nativeQuery = true)
    int incrementIssueCounter(@Param("id") UUID id);

    /** Lee el contador directamente de la base de datos (no de la entidad en memoria). */
    @Query("select p.issueCounter from Project p where p.id = :id")
    int findIssueCounter(@Param("id") UUID id);
}
