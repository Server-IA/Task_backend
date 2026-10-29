package com.taskflow.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Ciclo (sprint) de un proyecto. Un issue pertenece como máximo a un ciclo. Un ciclo cerrado ({@code closedAt})
 * ya no se edita ni recibe tareas, y puede tener su retrospectiva ({@link RetroNote}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cycles")
public class Cycle extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** Cuándo lo cerró el project manager; {@code null} mientras siga abierto. */
    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by_id")
    private User closedBy;

    /** Tareas sin terminar que el cierre pasó a otro ciclo. */
    @Column(name = "carried_to_cycle", nullable = false)
    private int carriedToCycle;

    /** Tareas sin terminar que el cierre sacó del ciclo (quedaron en el backlog, sin ciclo). */
    @Column(name = "carried_to_backlog", nullable = false)
    private int carriedToBacklog;

    /**
     * Foto del cierre para el resumen de la retrospectiva: tareas y bugs completados, el total (completados más los
     * que pasaron a otro ciclo o al backlog; sin historias ni canceladas) y la mediana del tiempo de ciclo. Así el
     * resumen no cambia si después una tarea se reabre o pasa a otro ciclo. {@code null} en los ciclos cerrados antes
     * de V25 (el resumen se calcula con las tareas que siguen en el ciclo).
     */
    @Column(name = "closed_completed_count")
    private Integer closedCompletedCount;

    @Column(name = "closed_total_count")
    private Integer closedTotalCount;

    @Column(name = "closed_cycle_time_days")
    private Double closedCycleTimeDays;

    /** La retrospectiva está abierta desde aquí (al cerrar el ciclo o cuando la abre el project manager). */
    @Column(name = "retro_started_at")
    private Instant retroStartedAt;

    /** Al terminar la retrospectiva sus notas y votos quedan de solo lectura. */
    @Column(name = "retro_finished_at")
    private Instant retroFinishedAt;

    @ManyToMany
    @JoinTable(name = "cycle_issues",
            joinColumns = @JoinColumn(name = "cycle_id"),
            inverseJoinColumns = @JoinColumn(name = "issue_id"))
    private Set<Issue> issues = new HashSet<>();

    public boolean isClosed() {
        return closedAt != null;
    }
}
