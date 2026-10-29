package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
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

    /** Historia, tarea o bug (ver {@link IssueType}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 10)
    private IssueType type = IssueType.TASK;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /**
     * Sube en 1 con cada cambio de la descripción (y solo de la descripción). Quien edita la manda como
     * {@code baseDescriptionVersion} para no pisar un cambio ajeno que no vio.
     */
    @Column(name = "description_version", nullable = false)
    private int descriptionVersion;

    /** Último cambio de la descripción; {@code null} si nunca tuvo. */
    @Column(name = "description_updated_at")
    private Instant descriptionUpdatedAt;

    /** Quién hizo el último cambio de la descripción. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "description_updated_by_id")
    private User descriptionUpdatedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id", nullable = false)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IssuePriority priority = IssuePriority.NONE;

    /**
     * Posición de la prioridad para ordenar con {@code sort=priorityRank,asc}: 0 urgente, 1 alta, 2 media, 3 baja y
     * 4 sin prioridad (el orden de {@link IssuePriority}). La prioridad se guarda como texto, así que
     * {@code sort=priority} la ordenaría alfabéticamente. Solo lectura.
     */
    @Formula("(case priority when 'URGENT' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 when 'LOW' then 3 "
            + "else 4 end)")
    private int priorityRank;

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

    /**
     * La primera vez que entró a un estado del grupo STARTED; {@code null} si nunca pasó por uno. No se borra al
     * salir del grupo: el tiempo de ciclo se mide desde aquí hasta {@link #completedAt}.
     */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * Último cambio de su checklist (agregar, editar, marcar, reordenar o borrar un elemento); {@code null} si nunca
     * tuvo. Cambiar la checklist no modifica {@code updatedAt}.
     */
    @Column(name = "checklist_updated_at")
    private Instant checklistUpdatedAt;

    /**
     * Historias: cuándo pasó a un estado cerrado (COMPLETED o CANCELLED); {@code null} mientras esté abierta. Se borra
     * al reabrirla.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /** Historias: quién la cerró. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by_id")
    private User closedBy;

    /**
     * Historias: ya se avisó a los project managers de que está «lista para revisar». Vuelve a {@code false} cuando
     * deja de estarlo, así el aviso sale una vez cada vez que pasa a estarlo.
     */
    @Column(name = "review_notified", nullable = false)
    private boolean reviewNotified;

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

    /** Tareas que bloquean a esta y siguen abiertas (ni completadas ni canceladas). */
    @Formula("(select count(*) from issue_relations r join issues b on b.id = r.source_issue_id "
            + "join states st on st.id = b.state_id "
            + "where r.target_issue_id = id and r.type = 'BLOCKS' "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED'))")
    private int blockedByCount;

    /**
     * Códigos de esas tareas separados por coma y ordenados (por ejemplo {@code AMC-4,WEB-2}); pueden ser de otros
     * proyectos del workspace. {@code null} si no tiene bloqueos abiertos. Se lee con {@link #getBlockedByCodes()}.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Formula("(select string_agg(p.identifier || '-' || b.sequence_id, ',' order by p.identifier, b.sequence_id) "
            + "from issue_relations r join issues b on b.id = r.source_issue_id "
            + "join states st on st.id = b.state_id join projects p on p.id = b.project_id "
            + "where r.target_issue_id = id and r.type = 'BLOCKS' "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED'))")
    private String blockedByCodesText;

    /**
     * Códigos de las tareas abiertas que esta bloquea, separados por coma y ordenados; solo mientras esta siga abierta
     * (una tarea cerrada ya no bloquea). Se lee con {@link #getBlockingCodes()}.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Formula("(select string_agg(p.identifier || '-' || b.sequence_id, ',' order by p.identifier, b.sequence_id) "
            + "from issue_relations r join issues b on b.id = r.target_issue_id "
            + "join states st on st.id = b.state_id join projects p on p.id = b.project_id "
            + "where r.source_issue_id = id and r.type = 'BLOCKS' "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED') "
            + "and exists (select 1 from states own where own.id = state_id "
            + "and own.state_group not in ('COMPLETED', 'CANCELLED')))")
    private String blockingCodesText;

    /** Pasos de la checklist ({@code CHECK}). */
    @Formula("(select count(*) from issue_checklist_items c where c.issue_id = id and c.kind = 'CHECK')")
    private int checklistTotal;

    @Formula("(select count(*) from issue_checklist_items c where c.issue_id = id and c.kind = 'CHECK' and c.done)")
    private int checklistDone;

    /** Criterios de aceptación ({@code CRITERION}). */
    @Formula("(select count(*) from issue_checklist_items c where c.issue_id = id and c.kind = 'CRITERION')")
    private int criteriaTotal;

    @Formula("(select count(*) from issue_checklist_items c "
            + "where c.issue_id = id and c.kind = 'CRITERION' and c.done)")
    private int criteriaDone;

    // ----- Historias (solo se calculan si la tarea es una historia; en las demás valen 0) -----

    /** Tareas ({@code TASK}) hijas de la historia, sin contar las canceladas: el «y» de «x de y tareas». */
    @Formula("(case when issue_type = 'STORY' then (select count(*) from issues s join states st on st.id = s.state_id "
            + "where s.parent_id = id and s.issue_type = 'TASK' and st.state_group <> 'CANCELLED') else 0 end)")
    private int storyTaskCount;

    /** Tareas hijas completadas: el «x» de «x de y tareas». */
    @Formula("(case when issue_type = 'STORY' then (select count(*) from issues s join states st on st.id = s.state_id "
            + "where s.parent_id = id and s.issue_type = 'TASK' and st.state_group = 'COMPLETED') else 0 end)")
    private int storyTaskDoneCount;

    /** Tareas hijas en curso (grupo STARTED), para la parte naranja de la barra de progreso. */
    @Formula("(case when issue_type = 'STORY' then (select count(*) from issues s join states st on st.id = s.state_id "
            + "where s.parent_id = id and s.issue_type = 'TASK' and st.state_group = 'STARTED') else 0 end)")
    private int storyTaskStartedCount;

    /** Bugs hijos abiertos (ni completados ni cancelados): «1 bug abierto». No cuentan en el progreso. */
    @Formula("(case when issue_type = 'STORY' then (select count(*) from issues s join states st on st.id = s.state_id "
            + "where s.parent_id = id and s.issue_type = 'BUG' "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED')) else 0 end)")
    private int openBugCount;

    /** Bugs hijos creados después de cerrar la historia: «1 bug después de cerrar» (0 si está abierta). */
    @Formula("(case when issue_type = 'STORY' and closed_at is not null then (select count(*) from issues s "
            + "where s.parent_id = id and s.issue_type = 'BUG' and s.created_at > closed_at) else 0 end)")
    private int bugsAfterCloseCount;

    /**
     * «Lista para revisar»: una historia abierta con al menos una tarea, todas sus tareas completadas o canceladas y
     * ningún bug ligado abierto. Es una marca calculada: el estado de la historia no cambia (sigue en In Progress) y
     * solo el project manager la cierra. La misma regla que {@code StoryRules.isReady}.
     */
    @Formula("(issue_type = 'STORY' "
            + "and exists (select 1 from states own where own.id = state_id "
            + "and own.state_group not in ('COMPLETED', 'CANCELLED')) "
            + "and exists (select 1 from issues s where s.parent_id = id and s.issue_type = 'TASK') "
            + "and not exists (select 1 from issues s join states st on st.id = s.state_id "
            + "where s.parent_id = id and s.issue_type in ('TASK', 'BUG') "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED')))")
    private boolean readyForReview;

    /** Códigos de las tareas abiertas que la bloquean, ordenados (vacío si no está bloqueada). */
    public List<String> getBlockedByCodes() {
        return splitCodes(blockedByCodesText);
    }

    /** Códigos de las tareas abiertas que bloquea mientras siga abierta, ordenados (vacío si no bloquea ninguna). */
    public List<String> getBlockingCodes() {
        return splitCodes(blockingCodesText);
    }

    private static List<String> splitCodes(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text.split(","));
    }

    /** Es una historia. */
    public boolean isStory() {
        return type == IssueType.STORY;
    }

    /** Código legible, por ejemplo {@code WEB-12}. Se calcula para que siga al identificador del proyecto. */
    public String getCode() {
        return project.getIdentifier() + "-" + sequenceId;
    }
}
