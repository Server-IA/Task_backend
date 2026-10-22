package com.taskflow.api.repository;

import com.taskflow.api.entity.Workspace;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    @EntityGraph(attributePaths = "owner")
    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
