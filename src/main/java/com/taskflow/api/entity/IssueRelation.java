package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.IssueRelationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Dependencia entre dos tareas del mismo proyecto. Con {@code BLOCKS}, {@code source} bloquea a {@code target};
 * {@code RELATES} es simétrica y se guarda una sola vez.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "issue_relations")
public class IssueRelation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_issue_id", nullable = false)
    private Issue source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_issue_id", nullable = false)
    private Issue target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IssueRelationType type;

    public IssueRelation(Issue source, Issue target, IssueRelationType type) {
        this.source = source;
        this.target = target;
        this.type = type;
    }

    /** La otra tarea de la relación, vista desde {@code issueId}. */
    public Issue otherThan(UUID issueId) {
        return source.getId().equals(issueId) ? target : source;
    }
}
