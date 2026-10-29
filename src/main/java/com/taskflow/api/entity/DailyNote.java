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

import java.time.LocalDate;

/**
 * Nota de la reunión diaria (daily) de un proyecto: como máximo una por día.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "daily_notes")
public class DailyNote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "note_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    /** Quien la escribió o editó por última vez; {@code null} si su cuenta se eliminó. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private User updatedBy;

    public DailyNote(Project project, LocalDate date) {
        this.project = project;
        this.date = date;
    }
}
