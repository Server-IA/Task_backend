package com.taskflow.api.repository;

import com.taskflow.api.entity.IssueTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueTemplateRepository extends JpaRepository<IssueTemplate, UUID> {

    /** Las etiquetas y la checklist se cargan por lotes ({@code default_batch_fetch_size}). */
    List<IssueTemplate> findAllByProjectIdOrderByPositionAscCreatedAtAsc(UUID projectId);

    Optional<IssueTemplate> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);

    /**
     * Suma un uso a la plantilla con un UPDATE atómico (dos tareas creadas a la vez cuentan las dos). Devuelve 0 si
     * la plantilla no existe en el proyecto.
     */
    @Modifying
    @Query(value = """
            UPDATE issue_templates SET usage_count = usage_count + 1
            WHERE id = :id AND project_id = :projectId
            """, nativeQuery = true)
    int incrementUsage(@Param("id") UUID id, @Param("projectId") UUID projectId);
}
