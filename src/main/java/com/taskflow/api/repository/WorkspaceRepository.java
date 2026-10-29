package com.taskflow.api.repository;

import com.taskflow.api.entity.Workspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    @EntityGraph(attributePaths = "owner")
    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Workspaces de los que el usuario es el dueño registrado (el límite de workspaces por persona). */
    long countByOwnerId(UUID ownerId);

    /**
     * Busca el workspace bloqueando su fila hasta el final de la transacción. Se usa al cambiar roles o
     * quitar miembros, para que dos cambios simultáneos no dejen el workspace sin project manager.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.slug = :slug")
    Optional<Workspace> findBySlugForUpdate(@Param("slug") String slug);
}
