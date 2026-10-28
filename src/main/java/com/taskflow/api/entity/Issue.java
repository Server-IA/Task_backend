package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.IssuePriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "issues")
public class Issue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Número del issue dentro del proyecto (el 12 de WEB-12). */
    @Column(name = "sequence_id", nullable = false, updatable = false)
    private int sequenceId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id", nullable = false)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IssuePriority priority = IssuePriority.NONE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Issue parent;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Posición dentro de la columna del Kanban (ver {@code SortOrderCalculator}). */
    @Column(name = "sort_order", nullable = false)
    private double sortOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    /**
     * Cuándo entró al grupo de estados COMPLETED; {@code null} mientras no esté completado. Se fija al entrar
     * al grupo y se borra al salir (moverlo entre dos estados completados no lo cambia).
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToMany
    @JoinTable(name = "issue_assignees",
            joinColumns = @JoinColumn(name = "issue_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> assignees = new HashSet<>();

    @ManyToMany
    @JoinTable(name = "issue_labels",
            joinColumns = @JoinColumn(name = "issue_id"),
            inverseJoinColumns = @JoinColumn(name = "label_id"))
    private Set<Label> labels = new HashSet<>();

    /** Lado inverso de cycle_issues (lo gestiona Cycle). Como máximo tiene un ciclo. */
    @ManyToMany(mappedBy = "issues")
    private Set<Cycle> cycles = new HashSet<>();

    /** Lado inverso de module_issues (lo gestiona ProjectModule). */
    @ManyToMany(mappedBy = "issues")
    private Set<ProjectModule> modules = new HashSet<>();

    /** Cantidad de sub-issues; se calcula con una subconsulta en el mismo SELECT (solo lectura). */
    @Formula("(select count(*) from issues s where s.parent_id = id)")
    private int subIssueCount;

    /** Sub-issues cuyo estado está en el grupo COMPLETED. */
    @Formula("(select count(*) from issues s join states st on st.id = s.state_id "
            + "where s.parent_id = id and st.state_group = 'COMPLETED')")
    private int completedSubIssueCount;

    /** Código legible, por ejemplo {@code WEB-12}. Se calcula para que siga al identificador del proyecto. */
    public String getCode() {
        return project.getIdentifier() + "-" + sequenceId;
    }
}
