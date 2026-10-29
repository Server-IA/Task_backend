package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.ProjectColor;
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
 * Proyecto de un workspace. Sus contadores (tareas, abiertas, vencidas, historias) no se cargan con la entidad: los
 * calcula {@code ProjectRepository.countIssues} solo donde se devuelven ({@code ProjectResponse}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 80)
    private String name;

    /** Prefijo del código de los issues, por ejemplo {@code WEB} en {@code WEB-12}. */
    @Column(nullable = false, length = 12)
    private String identifier;

    @Column(columnDefinition = "text")
    private String description;

    /** Color de la pastilla del proyecto (clave de la paleta). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ProjectColor color;

    /**
     * Último número de issue asignado. Solo lo modifica {@code ProjectRepository.incrementIssueCounter}
     * con un UPDATE atómico, por eso Hibernate nunca lo escribe.
     */
    @Column(name = "issue_counter", nullable = false, insertable = false, updatable = false)
    private int issueCounter;

    /**
     * Código que recibirá la próxima tarea, por ejemplo {@code WEB-26}. Es orientativo: si otra persona crea una
     * tarea antes, esa se lleva el número.
     */
    public String getNextIssueCode() {
        return identifier + "-" + (issueCounter + 1);
    }
}
