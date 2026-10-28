package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.IssuePriority;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plantilla de tarea de un proyecto: prefijo del título, esqueleto de la descripción, prioridad, etiquetas y
 * checklist con los que el frontend rellena el formulario de nueva tarea.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "issue_templates")
public class IssueTemplate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 60)
    private String name;

    /** Se antepone al título de la tarea, por ejemplo «[Bug] » (se guarda tal cual, con su espacio final). */
    @Column(name = "title_prefix", length = 40)
    private String titlePrefix;

    /** Esqueleto en markdown de la descripción. */
    @Column(columnDefinition = "text")
    private String description;

    /** {@code null}: la plantilla no fija prioridad. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private IssuePriority priority;

    @Column(nullable = false)
    private int position;

    /**
     * Tareas creadas con la plantilla. Solo lo modifica {@code IssueTemplateRepository.incrementUsage} con un UPDATE
     * atómico, por eso Hibernate nunca lo escribe.
     */
    @Column(name = "usage_count", nullable = false, insertable = false, updatable = false)
    private int usageCount;

    @ManyToMany
    @JoinTable(name = "issue_template_labels",
            joinColumns = @JoinColumn(name = "template_id"),
            inverseJoinColumns = @JoinColumn(name = "label_id"))
    @OrderBy("name ASC")
    private Set<Label> labels = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "issue_template_items", joinColumns = @JoinColumn(name = "template_id"))
    @OrderColumn(name = "position")
    private List<TemplateChecklistItem> checklist = new ArrayList<>();
}
