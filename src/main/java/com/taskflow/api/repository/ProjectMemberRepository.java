package com.taskflow.api.repository;

import com.taskflow.api.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    /** Miembros que trae el resumen de cada proyecto ({@code ProjectResponse.members}). */
    int MEMBER_PREVIEW = 5;

    /**
     * Resumen de los miembros de varios proyectos en una consulta: por proyecto, sus primeros {@code limit} miembros
     * por nombre, cada fila con el total de miembros del proyecto. Columnas: project_id, member_count, user_id,
     * email, full_name, avatar_url. Un proyecto sin miembros no aparece.
     */
    @Query(value = """
            SELECT ranked.project_id, ranked.member_count, ranked.user_id, ranked.email, ranked.full_name,
                   ranked.avatar_url
            FROM (SELECT pm.project_id, u.id AS user_id, u.email, u.full_name, u.avatar_url,
                         count(*) OVER (PARTITION BY pm.project_id) AS member_count,
                         row_number() OVER (PARTITION BY pm.project_id ORDER BY u.full_name, u.id) AS position
                  FROM project_members pm
                           JOIN users u ON u.id = pm.user_id
                  WHERE pm.project_id IN (:projectIds)) ranked
            WHERE ranked.position <= :limit
            ORDER BY ranked.project_id, ranked.position
            """, nativeQuery = true)
    List<Object[]> findMemberPreviews(@Param("projectIds") Collection<UUID> projectIds, @Param("limit") int limit);

    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

    /** El usuario es miembro del proyecto y el proyecto es del workspace indicado. */
    @Query("""
            select count(pm) > 0 from ProjectMember pm
            where pm.project.id = :projectId and pm.project.workspace.slug = :slug and pm.user.id = :userId
            """)
    boolean existsMemberInWorkspace(@Param("projectId") UUID projectId, @Param("slug") String slug,
                                    @Param("userId") UUID userId);

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    @Query("""
            select pm from ProjectMember pm
            join fetch pm.user u
            where pm.project.id = :projectId
            order by u.fullName
            """)
    List<ProjectMember> findAllByProjectIdWithUser(@Param("projectId") UUID projectId);

    @Query("select count(pm) from ProjectMember pm where pm.project.id = :projectId and pm.user.id in :userIds")
    long countMembers(@Param("projectId") UUID projectId, @Param("userIds") Collection<UUID> userIds);

    /** Al salir de un workspace, el usuario deja también sus proyectos. */
    @Modifying
    @Query("""
            delete from ProjectMember pm
            where pm.user.id = :userId
              and pm.project.id in (select p.id from Project p where p.workspace.id = :workspaceId)
            """)
    int deleteAllInWorkspace(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);
}
