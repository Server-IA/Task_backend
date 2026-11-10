package com.taskflow.api.repository;

import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.WorkspaceRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    /** Workspaces del usuario, con el workspace y su dueño ya cargados. */
    @Query("""
            select m from WorkspaceMember m
            join fetch m.workspace w
            join fetch w.owner
            left join fetch m.customRole
            where m.user.id = :userId
            order by w.name
            """)
    List<WorkspaceMember> findAllByUserIdWithWorkspace(@Param("userId") UUID userId);

    @Query("""
            select m from WorkspaceMember m
            join fetch m.user u
            left join fetch m.customRole
            where m.workspace.id = :workspaceId
            order by u.fullName
            """)
    List<WorkspaceMember> findAllByWorkspaceIdWithUser(@Param("workspaceId") UUID workspaceId);

    @Query("select m from WorkspaceMember m join fetch m.user join fetch m.workspace left join fetch m.customRole "
            + "where m.workspace.id = :workspaceId and m.user.id = :userId")
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(@Param("workspaceId") UUID workspaceId,
                                                         @Param("userId") UUID userId);

    @Query("select m.role from WorkspaceMember m where m.workspace.slug = :slug and m.user.id = :userId")
    Optional<WorkspaceRole> findRole(@Param("slug") String slug, @Param("userId") UUID userId);

    /**
     * La membresía con lo que hace falta para saber qué puede hacer la persona ({@code AccessControl}): el workspace
     * (permisos del rol «Miembro») y su rol propio, si tiene.
     */
    @Query("""
            select m from WorkspaceMember m
            join fetch m.workspace w
            left join fetch m.customRole
            where w.slug = :slug and m.user.id = :userId
            """)
    Optional<WorkspaceMember> findAccess(@Param("slug") String slug, @Param("userId") UUID userId);

    /** Cuántas personas tienen cada rol propio del workspace: filas {@code [id del rol, cantidad]}. */
    @Query("""
            select m.customRole.id, count(m) from WorkspaceMember m
            where m.workspace.id = :workspaceId and m.customRole is not null
            group by m.customRole.id
            """)
    List<Object[]> countByCustomRole(@Param("workspaceId") UUID workspaceId);

    /** Cuántas personas tienen ese rol base sin rol propio (los project managers, o quienes usan «Miembro»). */
    @Query("select count(m) from WorkspaceMember m where m.workspace.id = :workspaceId and m.role = :role "
            + "and m.customRole is null")
    long countWithBaseRole(@Param("workspaceId") UUID workspaceId, @Param("role") WorkspaceRole role);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    /** Miembros con ese rol, del más antiguo al más reciente, sin contar al usuario indicado. */
    @Query("""
            select m from WorkspaceMember m
            join fetch m.user u
            where m.workspace.id = :workspaceId and m.role = :role and u.id <> :excludedUserId
            order by m.createdAt asc
            """)
    List<WorkspaceMember> findOthersWithRole(@Param("workspaceId") UUID workspaceId,
                                             @Param("role") WorkspaceRole role,
                                             @Param("excludedUserId") UUID excludedUserId);

    boolean existsByWorkspaceIdAndUserEmail(UUID workspaceId, String email);

    /** Usuarios con ese rol en el workspace (por ejemplo, los project managers), por nombre. */
    @Query("""
            select m.user from WorkspaceMember m
            where m.workspace.id = :workspaceId and m.role = :role
            order by m.user.fullName
            """)
    List<com.taskflow.api.entity.User> findUsersWithRole(@Param("workspaceId") UUID workspaceId,
                                                         @Param("role") WorkspaceRole role);
}
