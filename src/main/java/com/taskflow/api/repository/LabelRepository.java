package com.taskflow.api.repository;

import com.taskflow.api.entity.Label;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabelRepository extends JpaRepository<Label, UUID> {

    List<Label> findAllByProjectIdOrderByNameAsc(UUID projectId);

    Optional<Label> findByIdAndProjectId(UUID id, UUID projectId);

    List<Label> findAllByProjectIdAndIdIn(UUID projectId, Collection<UUID> ids);

    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);
}
