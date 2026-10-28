package com.taskflow.api.repository;

import com.taskflow.api.entity.IssueTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueTemplateRepository extends JpaRepository<IssueTemplate, UUID> {

    /** Las etiquetas y la checklist se cargan por lotes ({@code default_batch_fetch_size}). */
    List<IssueTemplate> findAllByProjectIdOrderByPositionAscCreatedAtAsc(UUID projectId);

    Optional<IssueTemplate> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);
}
