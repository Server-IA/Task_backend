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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Ciclo (sprint) de un proyecto. Un issue pertenece como máximo a un ciclo.
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

    @ManyToMany
    @JoinTable(name = "cycle_issues",
            joinColumns = @JoinColumn(name = "cycle_id"),
            inverseJoinColumns = @JoinColumn(name = "issue_id"))
    private Set<Issue> issues = new HashSet<>();
}
