package com.taskflow.api.repository;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.StoredFile;
import com.taskflow.api.entity.enums.FileKind;
import org.springframework.data.domain.Limit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    /** Un archivo del proyecto (un id de otro proyecto no se encuentra). */
    @Query("""
            select f from StoredFile f
            left join fetch f.uploadedBy
            left join fetch f.issue
            where f.id = :id and f.project.id = :projectId
            """)
    Optional<StoredFile> findInProject(@Param("id") UUID id, @Param("projectId") UUID projectId);

    /**
     * Como {@link #findInProject}, bloqueando la fila hasta el final de la transacción: dos confirmaciones (o una
     * confirmación y un borrado) del mismo archivo no se cruzan.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from StoredFile f where f.id = :id and f.project.id = :projectId")
    Optional<StoredFile> lockInProject(@Param("id") UUID id, @Param("projectId") UUID projectId);

    /** Una foto de perfil del usuario, bloqueada hasta el final de la transacción. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select f from StoredFile f
            where f.id = :id and f.uploadedBy.id = :userId and f.kind = com.taskflow.api.entity.enums.FileKind.AVATAR
            """)
    Optional<StoredFile> lockAvatar(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Adjuntos confirmados de una tarea, del más antiguo al más reciente. */
    @Query("""
            select f from StoredFile f
            left join fetch f.uploadedBy
            where f.issue.id = :issueId and f.kind = com.taskflow.api.entity.enums.FileKind.ATTACHMENT
              and f.status = com.taskflow.api.entity.enums.FileStatus.READY
            order by f.createdAt, f.id
            """)
    List<StoredFile> findAttachments(@Param("issueId") UUID issueId);

    /** Espacio confirmado del workspace. */
    @Query("""
            select coalesce(sum(f.sizeBytes), 0) from StoredFile f
            where f.workspace.id = :workspaceId and f.status = com.taskflow.api.entity.enums.FileStatus.READY
            """)
    long sumReadyBytes(@Param("workspaceId") UUID workspaceId);

    /** Espacio confirmado más el de las subidas en curso (lo que se compara con la cuota al pedir una subida). */
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.workspace.id = :workspaceId")
    long sumReservedBytes(@Param("workspaceId") UUID workspaceId);

    /** Archivos confirmados con esos ids, con su proyecto (para ver quién puede pedir su URL). */
    @Query("""
            select f from StoredFile f
            left join fetch f.project
            where f.id in :ids and f.status = com.taskflow.api.entity.enums.FileStatus.READY
            """)
    List<StoredFile> findReadyByIds(@Param("ids") Collection<UUID> ids);

    /**
     * De esos proyectos, los que el usuario puede ver: es miembro de su workspace y además project manager de él o
     * miembro del proyecto (el mismo criterio que {@code WorkspaceSecurity.canAccessProject}).
     */
    @Query(value = """
            SELECT p.id FROM projects p
            WHERE p.id IN (:projectIds)
              AND EXISTS (SELECT 1 FROM workspace_members wm
                          WHERE wm.workspace_id = p.workspace_id AND wm.user_id = :userId
                            AND (wm.role = 'OWNER'
                                 OR EXISTS (SELECT 1 FROM project_members pm
                                            WHERE pm.project_id = p.id AND pm.user_id = :userId)))
            """, nativeQuery = true)
    List<UUID> findAccessibleProjectIds(@Param("projectIds") Collection<UUID> projectIds,
                                        @Param("userId") UUID userId);

    /** De esos usuarios, los que comparten al menos un workspace con {@code userId} (ven su foto de perfil). */
    @Query(value = """
            SELECT DISTINCT other.user_id FROM workspace_members other
            JOIN workspace_members mine ON mine.workspace_id = other.workspace_id
            WHERE mine.user_id = :userId AND other.user_id IN (:userIds)
            """, nativeQuery = true)
    List<UUID> findUsersSharingWorkspace(@Param("userIds") Collection<UUID> userIds, @Param("userId") UUID userId);

    /** Espacio por tipo guardado: filas {@code [contentType, bytes, cantidad]}. */
    @Query("""
            select f.contentType, sum(f.sizeBytes), count(f) from StoredFile f
            where f.workspace.id = :workspaceId and f.status = com.taskflow.api.entity.enums.FileStatus.READY
            group by f.contentType
            """)
    List<Object[]> usageByContentType(@Param("workspaceId") UUID workspaceId);

    /** Espacio por proyecto, del que más usa al que menos: filas {@code [projectId, bytes, cantidad]}. */
    @Query("""
            select f.project.id, sum(f.sizeBytes), count(f) from StoredFile f
            where f.workspace.id = :workspaceId and f.status = com.taskflow.api.entity.enums.FileStatus.READY
            group by f.project.id
            order by sum(f.sizeBytes) desc
            """)
    List<Object[]> usageByProject(@Param("workspaceId") UUID workspaceId);

    /** Los archivos más pesados del workspace, con su tarea, su proyecto y quién los subió. */
    @Query("""
            select f from StoredFile f
            join fetch f.project
            left join fetch f.issue
            left join fetch f.uploadedBy
            where f.workspace.id = :workspaceId and f.status = com.taskflow.api.entity.enums.FileStatus.READY
            order by f.sizeBytes desc, f.createdAt desc
            """)
    List<StoredFile> findLargest(@Param("workspaceId") UUID workspaceId, Limit limit);

    /** Fotos de perfil del usuario (la actual y las que haya que borrar). */
    List<StoredFile> findAllByUploadedByIdAndKind(UUID userId, FileKind kind);

    /**
     * Liga a la tarea recién creada las imágenes de su descripción que se subieron en «Nueva tarea» (sin tarea todavía):
     * así se borran con ella.
     */
    @Modifying
    @Query("""
            update StoredFile f set f.issue = :issue
            where f.id in :ids and f.project = :project and f.issue is null
              and f.kind = com.taskflow.api.entity.enums.FileKind.INLINE_IMAGE
            """)
    int claimInlineImages(@Param("ids") Collection<UUID> ids, @Param("project") Project project,
                          @Param("issue") Issue issue);

    // ----- Limpieza (StorageCleanup). Cada fila borrada deja su clave en storage_deletions (trigger) -----

    /** Subidas que nunca se confirmaron. */
    @Modifying
    @Query("""
            delete from StoredFile f
            where f.status = com.taskflow.api.entity.enums.FileStatus.PENDING and f.createdAt < :before
            """)
    int deletePendingBefore(@Param("before") Instant before);

    /**
     * Imágenes pegadas en un texto que ya no aparecen en ninguna descripción, comentario ni plantilla de su proyecto (se borraron
     * del texto, o nunca se guardó: se canceló la edición o «Nueva tarea»). Solo las subidas antes de {@code before},
     * para no tocar las de un texto que alguien sigue escribiendo.
     */
    @Modifying
    @Query(value = """
            DELETE FROM stored_files f
            WHERE f.kind = 'INLINE_IMAGE' AND f.status = 'READY' AND f.created_at < :before
              AND NOT EXISTS (SELECT 1 FROM issues i
                              WHERE i.project_id = f.project_id
                                AND strpos(i.description, '/files/' || f.id::text) > 0)
              AND NOT EXISTS (SELECT 1 FROM comments c JOIN issues i ON i.id = c.issue_id
                              WHERE i.project_id = f.project_id
                                AND strpos(c.body, '/files/' || f.id::text) > 0)
              AND NOT EXISTS (SELECT 1 FROM issue_templates t
                              WHERE t.project_id = f.project_id
                                AND strpos(t.description, '/files/' || f.id::text) > 0)
            """, nativeQuery = true)
    int deleteUnreferencedInlineImages(@Param("before") Instant before);

    /** Como {@link #deleteUnreferencedInlineImages}, solo con esos ids y sin esperar (al borrar un comentario). */
    @Modifying
    @Query(value = """
            DELETE FROM stored_files f
            WHERE f.id IN (:ids) AND f.project_id = :projectId AND f.kind = 'INLINE_IMAGE'
              AND NOT EXISTS (SELECT 1 FROM issues i
                              WHERE i.project_id = f.project_id
                                AND strpos(i.description, '/files/' || f.id::text) > 0)
              AND NOT EXISTS (SELECT 1 FROM comments c JOIN issues i ON i.id = c.issue_id
                              WHERE i.project_id = f.project_id
                                AND strpos(c.body, '/files/' || f.id::text) > 0)
              AND NOT EXISTS (SELECT 1 FROM issue_templates t
                              WHERE t.project_id = f.project_id
                                AND strpos(t.description, '/files/' || f.id::text) > 0)
            """, nativeQuery = true)
    int deleteUnreferencedInlineImagesIn(@Param("ids") Collection<UUID> ids, @Param("projectId") UUID projectId);

    /** Fotos de perfil de usuarios que ya no existen. */
    @Modifying
    @Query("""
            delete from StoredFile f
            where f.kind = com.taskflow.api.entity.enums.FileKind.AVATAR and f.uploadedBy is null
            """)
    int deleteOrphanAvatars();
}
