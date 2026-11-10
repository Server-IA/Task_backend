package com.taskflow.api.service;

import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.ReportOptions;
import com.taskflow.api.dto.response.IssuesReportPreview;

import java.util.UUID;

/**
 * Reportes de un proyecto. El de tareas usa los mismos filtros del listado más rangos de fechas.
 */
public interface ReportService {

    /** Lo que traerá el reporte con esos filtros: totales y repartos, sin el listado. */
    IssuesReportPreview previewIssues(String slug, UUID projectId, UUID userId, IssueFilter filter, ReportOptions options);

    /** El reporte de tareas en PDF. */
    ReportFile issuesPdf(String slug, UUID projectId, UUID userId, IssueFilter filter, ReportOptions options);

    /**
     * Un reporte listo para descargar.
     *
     * @param fileName nombre sugerido («reporte-tareas-PORT-2026-11-10.pdf»)
     * @param content  bytes del archivo
     */
    record ReportFile(String fileName, byte[] content) {
    }
}
