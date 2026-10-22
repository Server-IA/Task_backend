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

    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

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
