package com.taskflow.api.repository;

import com.taskflow.api.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * De los usuarios indicados, los que se pueden mencionar en una tarea del proyecto: sus miembros y los project
     * managers del workspace (que ven todos los proyectos).
     */
    @Query("""
            select u from User u
            where u.id in :ids
              and (exists (select 1 from ProjectMember pm where pm.project.id = :projectId and pm.user = u)
                   or exists (select 1 from WorkspaceMember wm
                              where wm.workspace.id = :workspaceId and wm.user = u
                                and wm.role = com.taskflow.api.entity.enums.WorkspaceRole.OWNER))
            """)
    List<User> findMentionable(@Param("ids") Collection<UUID> ids, @Param("projectId") UUID projectId,
                               @Param("workspaceId") UUID workspaceId);
}
