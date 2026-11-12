package com.taskflow.api.repository;

import com.taskflow.api.entity.ImportJob;
import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.entity.enums.ImportStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    @Query("select j from ImportJob j where j.id = :id and j.workspace.slug = :slug")
    Optional<ImportJob> findInWorkspace(@Param("id") UUID id, @Param("slug") String slug);

    /**
     * La importación con su fila bloqueada hasta el final de la transacción. Para lo que la cambia de paso (elegir,
     * empezar, deshacer): un doble clic o un reintento de red esperan y encuentran el estado ya cambiado, en vez de
     * lanzar dos veces el mismo trabajo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ImportJob j where j.id = :id and j.workspace.slug = :slug")
    Optional<ImportJob> findInWorkspaceForUpdate(@Param("id") UUID id, @Param("slug") String slug);

    /** El historial: las que llegaron a importar algo (o lo están haciendo), las más recientes primero. */
    @Query("""
            select j from ImportJob j left join fetch j.createdBy
            where j.workspace.id = :workspaceId and j.status in :statuses
            order by j.createdAt desc
            """)
    List<ImportJob> findHistory(@Param("workspaceId") UUID workspaceId, @Param("statuses") Collection<ImportStatus> statuses);

    /** La última importación terminada del mismo origen: sus equivalencias se proponen en la siguiente. */
    Optional<ImportJob> findFirstByWorkspaceIdAndSourceAndStatusOrderByCreatedAtDesc(UUID workspaceId, ImportSourceType source,
                                                                                    ImportStatus status);

    /** Las que quedaron a medias al apagarse el servidor (ver {@code ImportRunner}). */
    List<ImportJob> findAllByStatusIn(Collection<ImportStatus> statuses);
}
