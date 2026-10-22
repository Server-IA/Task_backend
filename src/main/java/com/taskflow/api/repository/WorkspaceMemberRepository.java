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
            where m.user.id = :userId
            order by w.name
            """)
    List<WorkspaceMember> findAllByUserIdWithWorkspace(@Param("userId") UUID userId);

    @Query("""
            select m from WorkspaceMember m
            join fetch m.user u
            where m.workspace.id = :workspaceId
            order by u.fullName
            """)
    List<WorkspaceMember> findAllByWorkspaceIdWithUser(@Param("workspaceId") UUID workspaceId);

    @Query("select m from WorkspaceMember m join fetch m.user where m.workspace.id = :workspaceId and m.user.id = :userId")
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(@Param("workspaceId") UUID workspaceId,
                                                         @Param("userId") UUID userId);

    @Query("select m.role from WorkspaceMember m where m.workspace.slug = :slug and m.user.id = :userId")
    Optional<WorkspaceRole> findRole(@Param("slug") String slug, @Param("userId") UUID userId);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    boolean existsByWorkspaceIdAndUserEmail(UUID workspaceId, String email);
}
