package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.ChecklistItemKind;
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

import java.time.Instant;

/**
 * Criterio de aceptación o paso de la checklist de una tarea. {@code position} ordena los elementos de cada
 * tipo dentro de la tarea (0, 1, 2…).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "issue_checklist_items")
public class IssueChecklistItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChecklistItemKind kind;

    @Column(nullable = false, length = 500)
    private String text;

    @Column(nullable = false)
    private boolean done = false;

    @Column(nullable = false)
    private int position;

    /** Quien lo marcó como hecho; {@code null} si no está marcado (o si se marcó antes de guardarse este dato). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_by_id")
    private User checkedBy;

    /** Cuándo se marcó como hecho; {@code null} si no está marcado. */
    @Column(name = "checked_at")
    private Instant checkedAt;

    public IssueChecklistItem(Issue issue, ChecklistItemKind kind, String text, int position) {
        this.issue = issue;
        this.kind = kind;
        this.text = text;
        this.position = position;
    }
}
