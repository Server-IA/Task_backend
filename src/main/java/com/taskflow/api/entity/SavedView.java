package com.taskflow.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Vista guardada de las tareas de un proyecto (filtros, lista o tablero y orden). Las personales solo las ve su
 * dueño; las compartidas, todo el proyecto, y solo el project manager las crea, edita o elimina.
 * {@code position} ordena las compartidas del proyecto por un lado y las personales de cada usuario por otro.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "saved_views")
public class SavedView extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Quien la creó. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false)
    private boolean shared = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private SavedViewFilters filters;

    @Column(nullable = false)
    private int position;
}
