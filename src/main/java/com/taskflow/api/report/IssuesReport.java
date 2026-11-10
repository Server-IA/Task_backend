package com.taskflow.api.report;

import com.taskflow.api.dto.request.ReportOptions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Datos de un reporte de tareas ya resueltos (nombres en vez de ids, conteos hechos y tareas agrupadas): lo que
 * dibuja {@link IssuesReportPdf} y lo que resume la vista previa.
 *
 * @param projectName   nombre del proyecto
 * @param identifier    identificador del proyecto («PORT»)
 * @param workspaceName nombre del workspace
 * @param generatedBy   nombre de quien lo pidió
 * @param generatedAt   cuándo se generó
 * @param zone          zona horaria para mostrar las fechas
 * @param filters       filtros aplicados, ya legibles («Estado: Todo, In Progress»); vacío si no hay ninguno
 * @param totals        totales de las tareas del reporte
 * @param byState       reparto por estado (en el orden del tablero, sin los que quedan en cero)
 * @param byPriority    reparto por prioridad (de urgente a sin prioridad)
 * @param byAssignee    reparto por persona (de más a menos; «Sin asignar» al final). Una tarea con dos asignados
 *                      cuenta para ambos
 * @param byType        reparto por tipo
 * @param groups        tareas agrupadas y ordenadas como se pidió
 * @param totalMatching tareas que cumplen los filtros (puede ser más que las que trae el reporte)
 * @param truncated     si el reporte solo trae las primeras {@link #MAX_ISSUES}
 * @param options       agrupación, orden y secciones pedidas
 */
public record IssuesReport(
        String projectName,
        String identifier,
        String workspaceName,
        String generatedBy,
        Instant generatedAt,
        ZoneId zone,
        List<FilterLine> filters,
        Totals totals,
        List<Count> byState,
        List<Count> byPriority,
        List<Count> byAssignee,
        List<Count> byType,
        List<Group> groups,
        long totalMatching,
        boolean truncated,
        ReportOptions options
) {

    /** Máximo de tareas en un reporte: con más, el reporte trae las primeras y lo avisa. */
    public static final int MAX_ISSUES = 1000;

    /** Un filtro aplicado: «Estado» → «Todo, In Progress». */
    public record FilterLine(String label, String value) {
    }

    /**
     * @param total     tareas del reporte
     * @param open      ni completadas ni canceladas
     * @param completed completadas
     * @param cancelled canceladas
     * @param overdue   abiertas con la fecha límite pasada
     */
    public record Totals(int total, int open, int completed, int cancelled, int overdue) {
    }

    /** Una fila de un reparto: nombre y cuántas tareas. */
    public record Count(String name, int count) {
    }

    /** Un grupo del listado («In Progress») con sus tareas; sin agrupar hay un solo grupo sin título. */
    public record Group(String title, List<Row> rows) {
    }

    /**
     * Una tarea del listado.
     *
     * @param closed  completada o cancelada
     * @param overdue abierta y con la fecha límite pasada
     */
    public record Row(
            String code,
            String type,
            String title,
            String state,
            String priority,
            List<String> assignees,
            List<String> labels,
            LocalDate dueDate,
            boolean closed,
            boolean overdue
    ) {
    }
}
