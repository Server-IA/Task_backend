package com.taskflow.api.repository;

import com.taskflow.api.entity.WorkspaceInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, UUID> {

    @Query("""
            select i from WorkspaceInvitation i
            join fetch i.workspace
            left join fetch i.invitedBy
            where i.tokenHash = :tokenHash
            """)
    Optional<WorkspaceInvitation> findByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("""
            select i from WorkspaceInvitation i
            left join fetch i.invitedBy
            where i.workspace.id = :workspaceId and i.accepted = false and i.expiresAt > :now
            order by i.createdAt desc
            """)
    List<WorkspaceInvitation> findPending(@Param("workspaceId") UUID workspaceId, @Param("now") Instant now);

    Optional<WorkspaceInvitation> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /** Al reinvitar un correo se descartan sus invitaciones anteriores sin aceptar. */
    @Modifying
    @Query("delete from WorkspaceInvitation i where i.workspace.id = :workspaceId and i.email = :email and i.accepted = false")
    int deleteNotAccepted(@Param("workspaceId") UUID workspaceId, @Param("email") String email);
}
