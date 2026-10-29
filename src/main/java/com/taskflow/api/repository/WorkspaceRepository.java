package com.taskflow.api.repository;

import com.taskflow.api.entity.Workspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * Bloquea la fila del workspace hasta el final de la transacción. Lo usan las invitaciones: dos invitaciones
     * simultáneas al mismo correo van en fila y la segunda reemplaza a la primera (una sola invitación vigente).
     * {@code NO KEY UPDATE}: no choca con las filas que solo apuntan al workspace.
     */
    @Query(value = "SELECT 1 FROM workspaces WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    List<Integer> lockById(@Param("id") UUID id);
}
