package com.taskflow.api.repository;

import com.taskflow.api.entity.CustomRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomRoleRepository extends JpaRepository<CustomRole, UUID> {

    /** Roles creados por el workspace, por nombre. */
    @Query("select r from CustomRole r where r.workspace.id = :workspaceId order by lower(r.name)")
    List<CustomRole> findAllByWorkspace(@Param("workspaceId") UUID workspaceId);

    Optional<CustomRole> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    long countByWorkspaceId(UUID workspaceId);

    /** Si ya hay un rol con ese nombre (sin importar mayúsculas), sin contar {@code excludedId}. */
    @Query("""
            select count(r) > 0 from CustomRole r
            where r.workspace.id = :workspaceId and lower(r.name) = lower(:name)
              and (:excludedId is null or r.id <> :excludedId)
            """)
    boolean existsByName(@Param("workspaceId") UUID workspaceId, @Param("name") String name,
                         @Param("excludedId") UUID excludedId);
}
