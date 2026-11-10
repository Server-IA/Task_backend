package com.taskflow.api.report;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.taskflow.api.dto.request.ReportOptions;
import com.taskflow.api.report.IssuesReport.Count;
import com.taskflow.api.report.IssuesReport.FilterLine;
import com.taskflow.api.report.IssuesReport.Group;
import com.taskflow.api.report.IssuesReport.Row;
import com.taskflow.api.report.IssuesReport.Totals;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El PDF del reporte: se genera, se puede leer y trae lo que debe (encabezado, filtros, resumen, listado y pie).
 */
class IssuesReportPdfTest {

    private static final ReportOptions DEFAULTS = new ReportOptions(null, null, null, null, null, null, null, null);

    private static Row row(int number, String title, boolean overdue) {
        return new Row("PORT-" + number, "Tarea", title, "In Progress", "Alta", List.of("Valentina Ríos", "Mateo Herrera"),
                List.of("frontend"), LocalDate.of(2026, 11, 4), false, overdue);
    }

    private static IssuesReport report(List<FilterLine> filters, List<Group> groups, ReportOptions options, boolean truncated) {
        int total = groups.stream().mapToInt(group -> group.rows().size()).sum();
        return new IssuesReport("Portal de Clientes", "PORT", "Equipo Producto", "Valentina Ríos",
                Instant.parse("2026-11-10T18:15:00Z"), ZoneId.of("America/Bogota"), filters,
                new Totals(total, total, 0, 0, total == 0 ? 0 : 1),
                total == 0 ? List.of() : List.of(new Count("In Progress", total)),
                total == 0 ? List.of() : List.of(new Count("Alta", total)),
                total == 0 ? List.of() : List.of(new Count("Valentina Ríos", total), new Count("Sin asignar", 1)),
                total == 0 ? List.of() : List.of(new Count("Tarea", total)),
                groups, truncated ? 1500 : total, truncated, options);
    }

    private static String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            // En una línea: el extractor parte las celdas que pasan de renglón y separa el total de páginas del pie
            return text.toString().replaceAll("[\\s\\u00A0]+", " ");
        } finally {
            reader.close();
        }
    }

    private static int pages(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getNumberOfPages();
        } finally {
            reader.close();
        }
    }

    @Test
    void traeElEncabezadoLosFiltrosElResumenYElListado() throws IOException {
        byte[] pdf = IssuesReportPdf.render(report(
                List.of(new FilterLine("Estado", "Todo, In Progress"), new FilterLine("Asignado", "Valentina Ríos")),
                List.of(new Group("In Progress", List.of(
                        row(26, "Tabla del historial con filtros por fecha y estado", true),
                        row(29, "[Bug] La sesión se cierra al refrescar la página en Safari", false)))),
                DEFAULTS, false));

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text)
                .contains("Reporte de tareas")
                .contains("Portal de Clientes (PORT)")
                .contains("Equipo Producto")
                .contains("TaskFlow")
                .contains("por Valentina Ríos")
                .contains("ESTADO")
                .contains("Todo, In Progress")
                .contains("// RESUMEN")
                .contains("VENCIDAS")
                .contains("Por persona")
                .contains("PORT-26")
                .contains("Tabla del historial con filtros por fecha y estado")
                .contains("Valentina Ríos, Mateo Herrera")
                .contains("4 nov 2026")
                .contains("Página 1 de 1");
    }

    @Test
    void sinFiltrosLoDiceYSinTareasNoDibujaLaTabla() throws IOException {
        String text = text(IssuesReportPdf.render(report(List.of(), List.of(), DEFAULTS, false)));

        assertThat(text)
                .contains("Ninguno: todas las tareas del proyecto.")
                .contains("No hay tareas que cumplan estos filtros.")
                .doesNotContain("CÓDIGO");
    }

    @Test
    void lasSeccionesQueSeQuitanNoAparecen() throws IOException {
        List<Group> groups = List.of(new Group(null, List.of(row(1, "Única", false))));

        String onlyIssues = text(IssuesReportPdf.render(report(List.of(), groups,
                new ReportOptions(null, null, null, null, ReportOptions.GroupBy.NONE, ReportOptions.Sort.TITLE, false, true), false)));
        assertThat(onlyIssues).doesNotContain("// RESUMEN").contains("SIN AGRUPAR, ORDEN: TÍTULO").contains("PORT-1");

        String onlySummary = text(IssuesReportPdf.render(report(List.of(), groups,
                new ReportOptions(null, null, null, null, null, null, true, false), false)));
        assertThat(onlySummary).contains("// RESUMEN").doesNotContain("PORT-1");
    }

    @Test
    void unListadoLargoPasaDePaginaYNumeraElTotal() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (int number = 1; number <= 120; number++) {
            rows.add(row(number, "Tarea número " + number + " con un título suficientemente largo para ocupar su celda", false));
        }
        byte[] pdf = IssuesReportPdf.render(report(List.of(), List.of(new Group("In Progress", rows)), DEFAULTS, true));

        int pages = pages(pdf);
        assertThat(pages).isGreaterThan(2);
        String text = text(pdf);
        assertThat(text)
                .contains("Página 1 de " + pages)
                .contains("Página " + pages + " de " + pages)
                .contains("PORT-120")
                // Avisa que hay más tareas de las que trae
                .contains("Hay 1500 tareas con estos filtros");
        // El encabezado de columnas se repite en cada página del listado
        assertThat(text.split("CÓDIGO", -1).length - 1).isGreaterThanOrEqualTo(pages - 1);
    }

    @Test
    void unTituloConEmojisOUnaPalabraLarguisimaNoRompeElPdf() throws IOException {
        String text = text(IssuesReportPdf.render(report(List.of(new FilterLine("Búsqueda", "«🚀 → login»")),
                List.of(new Group("Todo", List.of(
                        row(7, "Lanzar 🚀 " + "a".repeat(400), false),
                        row(8, "日本語", false)))),
                DEFAULTS, false)));

        assertThat(text).contains("PORT-7").contains("PORT-8").contains("-> login");
    }
}
