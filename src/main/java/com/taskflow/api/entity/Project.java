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
import org.hibernate.annotations.Formula;

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
     * Tareas y bugs actuales del proyecto (las historias no cuentan); se calcula con una subconsulta en el mismo SELECT
     * (solo lectura).
     */
    @Formula("(select count(*) from issues i where i.project_id = id and i.issue_type <> 'STORY')")
    private int issueCount;

    /** Historias abiertas (ni completadas ni canceladas). */
    @Formula("(select count(*) from issues i join states st on st.id = i.state_id "
            + "where i.project_id = id and i.issue_type = 'STORY' "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED'))")
    private int openStoryCount;

    /** Historias abiertas «sin partir»: sin ninguna tarea ({@code TASK}) hija. */
    @Formula("(select count(*) from issues i join states st on st.id = i.state_id "
            + "where i.project_id = id and i.issue_type = 'STORY' "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED') "
            + "and not exists (select 1 from issues c where c.parent_id = i.id and c.issue_type = 'TASK'))")
    private int unsplitStoryCount;

    /** Tareas y bugs que no están completados ni cancelados (las historias no cuentan: avanzan con sus tareas). */
    @Formula("(select count(*) from issues i join states st on st.id = i.state_id "
            + "where i.project_id = id and i.issue_type <> 'STORY' "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED'))")
    private int openIssueCount;

    /** Tareas y bugs abiertos con la fecha límite ya pasada. */
    @Formula("(select count(*) from issues i join states st on st.id = i.state_id "
            + "where i.project_id = id and i.issue_type <> 'STORY' "
            + "and st.state_group not in ('COMPLETED', 'CANCELLED') and i.due_date < current_date)")
    private int overdueIssueCount;

    /**
     * Código que recibirá la próxima tarea, por ejemplo {@code WEB-26}. Es orientativo: si otra persona crea una
     * tarea antes, esa se lleva el número.
     */
    public String getNextIssueCode() {
        return identifier + "-" + (issueCounter + 1);
    }
}
