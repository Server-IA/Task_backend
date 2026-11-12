package com.taskflow.api.report;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.taskflow.api.dto.request.ReportOptions;
import com.taskflow.api.dto.response.DevelopmentStatsResponse;
import com.taskflow.api.dto.response.DevelopmentStatsResponse.MergedWeek;
import com.taskflow.api.dto.response.DevelopmentStatsResponse.WorkflowStats;
import com.taskflow.api.i18n.Language;
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

    private static final ReportOptions DEFAULTS = new ReportOptions(null, null, null, null, null, null, null, null, null, null);

    private static Row row(int number, String title, boolean overdue) {
        return new Row("PORT-" + number, "Tarea", title, "In Progress", "Alta", List.of("Valentina Ríos", "Mateo Herrera"),
                List.of("frontend"), LocalDate.of(2026, 11, 4), false, overdue);
    }

    private static IssuesReport report(List<FilterLine> filters, List<Group> groups, ReportOptions options, boolean truncated) {
        return report(filters, groups, options, truncated, null);
    }

    private static IssuesReport report(List<FilterLine> filters, List<Group> groups, ReportOptions options, boolean truncated,
                                       DevelopmentStatsResponse development) {
        int total = groups.stream().mapToInt(group -> group.rows().size()).sum();
        return new IssuesReport("Portal de Clientes", "PORT", "Equipo Producto", "Valentina Ríos",
                Instant.parse("2026-11-10T18:15:00Z"), ZoneId.of("America/Bogota"), filters,
                new Totals(total, total, 0, 0, total == 0 ? 0 : 1),
                total == 0 ? List.of() : List.of(new Count("In Progress", total)),
                total == 0 ? List.of() : List.of(new Count("Alta", total)),
                total == 0 ? List.of() : List.of(new Count("Valentina Ríos", total), new Count("Sin asignar", 1)),
                total == 0 ? List.of() : List.of(new Count("Tarea", total)),
                groups, truncated ? 1500 : total, truncated, options, development);
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
    void enInglesLosTextosFijosYLasFechasSalenEnIngles() throws IOException {
        byte[] pdf = IssuesReportPdf.render(report(
                List.of(new FilterLine("State", "Todo, In Progress")),
                List.of(new Group("In Progress", List.of(row(26, "Tabla del historial con filtros por fecha y estado", true)))),
                DEFAULTS, false), Language.EN);

        String text = text(pdf);
        assertThat(text)
                .contains("Task report")
                .contains("// APPLIED FILTERS")
                .contains("// SUMMARY")
                .contains("OVERDUE")
                .contains("By person")
                .contains("TITLE")
                .contains("by Valentina Ríos")
                .contains("Nov 4, 2026")
                .contains("Page 1 of 1")
                // Lo que escribió la gente no se traduce
                .contains("Tabla del historial con filtros por fecha y estado")
                .doesNotContain("Reporte de tareas")
                .doesNotContain("Página");
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
                new ReportOptions(null, null, null, null, ReportOptions.GroupBy.NONE, ReportOptions.Sort.TITLE, false, true, null, null), false)));
        assertThat(onlyIssues).doesNotContain("// RESUMEN").contains("SIN AGRUPAR, ORDEN: TÍTULO").contains("PORT-1");

        String onlySummary = text(IssuesReportPdf.render(report(List.of(), groups,
                new ReportOptions(null, null, null, null, null, null, true, false, null, null), false)));
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

    @Test
    void laSeccionDeDesarrolloTraeLasCifrasYLaTablaPorWorkflow() throws IOException {
        DevelopmentStatsResponse development = new DevelopmentStatsResponse(1, 4, LocalDate.of(2026, 10, 19), 3, 1, 6, 30.5,
                8, 6, 10, 4, List.of(new MergedWeek(LocalDate.of(2026, 10, 19), 6)),
                List.of(new WorkflowStats("acme/portal", "CI", 7, 5, 200L, Instant.parse("2026-11-10T15:00:00Z"), "failure"),
                        new WorkflowStats("acme/portal", "Deploy", 1, 1, 45L, Instant.parse("2026-11-09T15:00:00Z"), "success")),
                List.of());
        List<Group> groups = List.of(new Group(null, List.of(row(1, "Única", false))));

        String text = text(IssuesReportPdf.render(report(List.of(), groups, DEFAULTS, false, development)));

        assertThat(text)
                .contains("// DESARROLLO (GITHUB) · ÚLTIMAS 4 SEMANAS")
                .contains("1 en borrador")
                // 30,5 horas de apertura a fusión
                .contains("30,5 h")
                // 6 de 8 ejecuciones correctas y 4 de 10 tareas completadas con su PR fusionado
                .contains("75 %").contains("6 de 8 ejecuciones")
                .contains("40 %").contains("4 de 10 completadas")
                .contains("CI").contains("acme/portal").contains("3 min 20 s").contains("fallida")
                .contains("Deploy").contains("45 s").contains("correcta");

        String english = text(IssuesReportPdf.render(report(List.of(), groups, DEFAULTS, false, development), Language.EN));
        assertThat(english).contains("// DEVELOPMENT (GITHUB) · LAST 4 WEEKS").contains("6 of 8 runs").contains("failed");

        // Sin la sección (no se pidió o no hay repositorios) no aparece
        assertThat(text(IssuesReportPdf.render(report(List.of(), groups, DEFAULTS, false)))).doesNotContain("DESARROLLO");
    }

    @Test
    void lasDuracionesYLosTiemposSeLeenFacil() {
        assertThat(IssuesReportPdf.durationText(null)).isEqualTo("—");
        assertThat(IssuesReportPdf.durationText(45L)).isEqualTo("45 s");
        assertThat(IssuesReportPdf.durationText(120L)).isEqualTo("2 min");
        assertThat(IssuesReportPdf.durationText(3900L)).isEqualTo("1 h 05 min");
        assertThat(IssuesReportPdf.hoursText(null, Language.ES)).isEqualTo("—");
        assertThat(IssuesReportPdf.hoursText(0.2, Language.ES)).isEqualTo("12 min");
        assertThat(IssuesReportPdf.hoursText(5.0, Language.ES)).isEqualTo("5 h");
        assertThat(IssuesReportPdf.hoursText(72.0, Language.ES)).isEqualTo("3 días");
        assertThat(IssuesReportPdf.hoursText(84.0, Language.EN)).isEqualTo("3.5 days");
    }
}
