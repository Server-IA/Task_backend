package com.taskflow.api.repository;

import com.taskflow.api.entity.IssueChecklistItem;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueChecklistItemRepository extends JpaRepository<IssueChecklistItem, UUID> {

    /** Con quien marcó cada elemento cargado. */
    @EntityGraph(attributePaths = "checkedBy")
    List<IssueChecklistItem> findAllByIssueIdOrderByPositionAscCreatedAtAsc(UUID issueId);

    /** Elementos de un tipo en su orden; se usa para agregar al final y para reordenar. */
    List<IssueChecklistItem> findAllByIssueIdAndKindOrderByPositionAscCreatedAtAsc(UUID issueId, ChecklistItemKind kind);

    Optional<IssueChecklistItem> findByIdAndIssueId(UUID id, UUID issueId);

    long countByIssueId(UUID issueId);
}
