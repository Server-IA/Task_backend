package com.taskflow.api.dto.request;

import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Opciones de un reporte de tareas, además de los filtros del listado ({@link IssueFilter}): rangos de fechas, cómo
 * se agrupan y ordenan las tareas y qué secciones lleva (resumen, listado y desarrollo).
 */
public record ReportOptions(
        @Parameter(description = "Creadas desde este día (YYYY-MM-DD), inclusive")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate createdFrom,

        @Parameter(description = "Creadas hasta este día (YYYY-MM-DD), inclusive")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate createdTo,

        @Parameter(description = "Con fecha límite desde este día (YYYY-MM-DD), inclusive")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate dueFrom,

        @Parameter(description = "Con fecha límite hasta este día (YYYY-MM-DD), inclusive")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate dueTo,

        @Parameter(description = "Cómo se agrupa el listado: NONE, STATE (por defecto), PRIORITY, ASSIGNEE o TYPE")
        GroupBy groupBy,

        @Parameter(description = "Orden dentro de cada grupo: PRIORITY (por defecto), NEWEST, OLDEST, UPDATED, "
                + "DUE_DATE, TITLE o MANUAL (el del tablero)")
        Sort sort,

        @Parameter(description = "Incluir el resumen (totales y reparto por estado, prioridad, persona y tipo); por "
                + "defecto true")
        Boolean summary,

        @Parameter(description = "Incluir el listado de tareas; por defecto true")
        Boolean issues,

        @Parameter(description = "Incluir la sección de desarrollo (pull requests y GitHub Actions de los repositorios "
                + "enlazados al proyecto); por defecto false. Sin repositorios enlazados no sale")
        Boolean development,

        @Parameter(description = "Semanas que cubre la sección de desarrollo: 4, 8 (por defecto) o 12")
        Integer developmentWeeks
) {

    /** Semanas que cubre la sección de desarrollo si no se dice. */
    public static final int DEFAULT_DEVELOPMENT_WEEKS = 8;

    /** Agrupación del listado. */
    public enum GroupBy {
        NONE, STATE, PRIORITY, ASSIGNEE, TYPE
    }

    /** Orden del listado. */
    public enum Sort {
        PRIORITY, NEWEST, OLDEST, UPDATED, DUE_DATE, TITLE, MANUAL
    }

    public GroupBy groupByOrDefault() {
        return groupBy != null ? groupBy : GroupBy.STATE;
    }

    public Sort sortOrDefault() {
        return sort != null ? sort : Sort.PRIORITY;
    }

    public boolean includeSummary() {
        return summary == null || summary;
    }

    public boolean includeIssues() {
        return issues == null || issues;
    }

    public boolean includeDevelopment() {
        return development != null && development;
    }

    public int developmentWeeksOrDefault() {
        return developmentWeeks != null ? developmentWeeks : DEFAULT_DEVELOPMENT_WEEKS;
    }
}
