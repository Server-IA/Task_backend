package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.entity.enums.ImportStatus;
import com.taskflow.api.entity.enums.ImportVia;
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

import java.time.Instant;

/**
 * Una importación desde otra herramienta. Los campos {@code discovery}, {@code selection}, {@code mapping},
 * {@code progress} y {@code report} son JSON en texto (ver el paquete {@code imports}): qué se encontró en el origen,
 * qué se eligió traer, las equivalencias, por dónde va y qué resultó.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "import_jobs")
public class ImportJob extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportSourceType source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ImportVia via;

    /** De dónde, como se le muestra a la persona: el sitio de Jira, el nombre del archivo… */
    @Column(name = "source_label", nullable = false, length = 200)
    private String sourceLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportStatus status = ImportStatus.DRAFT;

    @Column(columnDefinition = "text")
    private String discovery;

    @Column(columnDefinition = "text")
    private String selection;

    @Column(columnDefinition = "text")
    private String mapping;

    @Column(columnDefinition = "text")
    private String progress;

    @Column(columnDefinition = "text")
    private String report;

    /** Por qué falló (lo que se le muestra a la persona), si falló. */
    @Column(length = 500)
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
