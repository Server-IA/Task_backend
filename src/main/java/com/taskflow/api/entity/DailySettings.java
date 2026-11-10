package com.taskflow.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ajustes de la daily de un proyecto (como máximo una fila por proyecto; sin fila valen los valores por defecto):
 * el tiempo sugerido por persona en «Presentar», el de quienes tienen uno distinto, qué bloques se exponen y los
 * puntos fijos a tratar.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "daily_settings")
public class DailySettings extends BaseEntity {

    /** Tiempo por persona cuando el proyecto no lo ha cambiado. */
    public static final int DEFAULT_SECONDS = 120;
    public static final int MIN_SECONDS = 15;
    public static final int MAX_SECONDS = 3600;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "seconds_per_person", nullable = false)
    private int secondsPerPerson = DEFAULT_SECONDS;

    @Column(name = "show_summary", nullable = false)
    private boolean showSummary = true;

    @Column(name = "show_yesterday", nullable = false)
    private boolean showYesterday = true;

    @Column(name = "show_today", nullable = false)
    private boolean showToday = true;

    @Column(name = "show_blockers", nullable = false)
    private boolean showBlockers = true;

    @Column(name = "show_note", nullable = false)
    private boolean showNote = true;

    /** Id de usuario (texto) → segundos, solo de quienes tienen un tiempo distinto al general. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "person_seconds", nullable = false, columnDefinition = "jsonb")
    private Map<String, Integer> personSeconds = new LinkedHashMap<>();

    /** Puntos a tratar, en orden. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> agenda = new ArrayList<>();

    /** Quien los cambió por última vez; {@code null} si su cuenta se eliminó. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private User updatedBy;

    public DailySettings(Project project) {
        this.project = project;
    }
}
