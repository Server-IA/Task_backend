package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.StateGroup;
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

/**
 * Columna del Kanban de un proyecto (Backlog, Todo, In Progress…).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "states")
public class State extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 7)
    private String color;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_group", nullable = false, length = 20)
    private StateGroup stateGroup;

    @Column(nullable = false)
    private int position;

    public State(Project project, String name, String color, StateGroup stateGroup, int position) {
        this.project = project;
        this.name = name;
        this.color = color;
        this.stateGroup = stateGroup;
        this.position = position;
    }
}
