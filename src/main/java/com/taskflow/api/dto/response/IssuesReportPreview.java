package com.taskflow.api.dto.response;

import com.taskflow.api.report.IssuesReport.Count;
import com.taskflow.api.report.IssuesReport.Totals;

import java.util.List;

/**
 * Vista previa de un reporte de tareas: lo que va a traer el PDF con esos filtros, sin el listado.
 *
 * @param totalMatching tareas que cumplen los filtros
 * @param truncated     si el reporte solo traerá las primeras {@code maxIssues}
 * @param maxIssues     máximo de tareas de un reporte
 * @param totals        totales de las tareas del reporte (total, abiertas, completadas, canceladas y vencidas)
 * @param byState       reparto por estado, en el orden del tablero
 * @param byPriority    reparto por prioridad, de urgente a sin prioridad
 * @param byAssignee    reparto por persona («Sin asignar» al final)
 * @param byType        reparto por tipo
 * @param githubRepositories repositorios de GitHub enlazados al proyecto (sin ninguno no se ofrece la sección de
 *                      desarrollo)
 * @param development   la sección de desarrollo (con {@code developmentWeeks}), esté encendida o no, si hay
 *                      repositorios; {@code null} si no hay ninguno
 */
public record IssuesReportPreview(
        long totalMatching,
        boolean truncated,
        int maxIssues,
        Totals totals,
        List<Count> byState,
        List<Count> byPriority,
        List<Count> byAssignee,
        List<Count> byType,
        int githubRepositories,
        DevelopmentStatsResponse development
) {
}
