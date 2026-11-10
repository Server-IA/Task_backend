package com.taskflow.api.controller;

import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.ReportOptions;
import com.taskflow.api.dto.response.IssuesReportPreview;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.ReportService;
import com.taskflow.api.service.ReportService.ReportFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Tag(name = "Reportes", description = "Reportes de un proyecto en PDF")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/reports")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "Vista previa del reporte de tareas",
            description = "Mismos filtros del listado de tareas (state, priority, assignee, label, type, cycle, module, "
                    + "blocked, search) más createdFrom/createdTo y dueFrom/dueTo (YYYY-MM-DD, inclusive). Devuelve los "
                    + "totales y los repartos que llevará el PDF. Un reporte trae como mucho maxIssues tareas "
                    + "(truncated = hay más).")
    @GetMapping("/issues")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'REPORT_GENERATE')")
    public IssuesReportPreview previewIssues(
            @PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
            @ParameterObject IssueFilter filter, @ParameterObject ReportOptions options) {
        return reportService.previewIssues(slug, projectId, me.getId(), filter, options);
    }

    @Operation(summary = "Reporte de tareas en PDF",
            description = "PDF A4 horizontal con el logo, los filtros aplicados, el resumen (summary=false lo quita) y "
                    + "el listado (issues=false lo quita) agrupado por groupBy y ordenado por sort. 400 si se quitan las "
                    + "dos secciones o un rango de fechas está al revés.")
    @GetMapping(value = "/issues/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'REPORT_GENERATE')")
    public ResponseEntity<byte[]> issuesPdf(
            @PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
            @ParameterObject IssueFilter filter, @ParameterObject ReportOptions options) {
        ReportFile file = reportService.issuesPdf(slug, projectId, me.getId(), filter, options);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                // Lleva datos del proyecto: no se guarda en cachés intermedias
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }
}
