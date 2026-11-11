package com.taskflow.api.report;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.taskflow.api.dto.request.ReportOptions;
import com.taskflow.api.i18n.Language;
import com.taskflow.api.report.IssuesReport.Count;
import com.taskflow.api.report.IssuesReport.FilterLine;
import com.taskflow.api.report.IssuesReport.Group;
import com.taskflow.api.report.IssuesReport.Row;
import com.taskflow.api.report.IssuesReport.Totals;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Dibuja el reporte de tareas en un PDF A4 horizontal: encabezado con el logo de TaskFlow, los filtros aplicados, el
 * resumen (totales y repartos con cantidad y porcentaje) y el listado en una tabla cuyo encabezado se repite en cada página. Todo
 * el texto va en celdas que ajustan su alto al contenido, así nada se monta sobre lo de al lado, y cada página lleva
 * su pie con «Página N de M».
 * <p>
 * Usa las fuentes estándar del PDF (Helvetica y Courier): por eso los textos pasan por {@link PdfText#clean}.
 * <p>
 * Los textos fijos (títulos, encabezados, avisos, «Página N de M») y las fechas salen en el idioma que se pide
 * ({@link Language#t}); los datos del reporte ya vienen en ese idioma.
 */
public final class IssuesReportPdf {

    // Colores del tema claro de TaskFlow
    private static final Color INK = new Color(0x23, 0x20, 0x1B);
    private static final Color MUTED = new Color(0x67, 0x61, 0x5A);
    private static final Color LINE = new Color(0xE6, 0xE2, 0xDA);
    private static final Color COLUMN = new Color(0xEA, 0xE7, 0xE1);
    private static final Color SUBTLE = new Color(0xF4, 0xF2, 0xEE);
    private static final Color GOLD = new Color(0x9A, 0x74, 0x28);
    private static final Color CREAM = new Color(0xEA, 0xD9, 0xB0);
    private static final Color LATE = new Color(0xA3, 0x27, 0x1C);
    private static final Color OK = new Color(0x1D, 0x6B, 0x47);

    private static final Font TITLE = new Font(Font.HELVETICA, 20, Font.BOLD, INK);
    private static final Font BRAND = new Font(Font.HELVETICA, 12, Font.BOLD, INK);
    private static final Font BODY = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, INK);
    private static final Font BODY_BOLD = new Font(Font.HELVETICA, 8.5f, Font.BOLD, INK);
    private static final Font BODY_MUTED = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED);
    private static final Font BODY_LATE = new Font(Font.HELVETICA, 8.5f, Font.BOLD, LATE);
    private static final Font SUBTITLE = new Font(Font.HELVETICA, 10, Font.NORMAL, MUTED);
    private static final Font CODE = new Font(Font.COURIER, 8, Font.NORMAL, MUTED);
    private static final Font CODE_INK = new Font(Font.COURIER, 8.5f, Font.BOLD, INK);
    private static final Font LABEL = new Font(Font.COURIER, 7.5f, Font.BOLD, MUTED);
    private static final Font NUMBER = new Font(Font.HELVETICA, 20, Font.BOLD, INK);
    private static final Font NUMBER_LATE = new Font(Font.HELVETICA, 20, Font.BOLD, LATE);
    private static final Font NUMBER_OK = new Font(Font.HELVETICA, 20, Font.BOLD, OK);
    private static final Font GROUP = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    private static final Font PANEL_TITLE = new Font(Font.HELVETICA, 9.5f, Font.BOLD, INK);

    /** Filas que muestra cada reparto del resumen; si hay más, la última dice cuántas faltan. */
    private static final int MAX_BREAKDOWN_ROWS = 8;

    /** Anchos de las columnas del listado (suman el ancho útil de la página). */
    private static final float[] ISSUE_COLUMNS = {58, 46, 246, 86, 64, 122, 90, 58};
    private static final String[] ISSUE_HEADERS =
            {"Código", "Tipo", "Título", "Estado", "Prioridad", "Asignados", "Etiquetas", "Vence"};

    // «10 nov 2026, 21:09» en español y «Nov 10, 2026, 9:09 PM» en inglés
    private static final DateTimeFormatter DAY_ES = DateTimeFormatter.ofPattern("d MMM yyyy", Language.ES.locale());
    private static final DateTimeFormatter DAY_EN = DateTimeFormatter.ofPattern("MMM d, yyyy", Language.EN.locale());
    private static final DateTimeFormatter MOMENT_ES =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Language.ES.locale());
    private static final DateTimeFormatter MOMENT_EN =
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Language.EN.locale());

    private IssuesReportPdf() {
    }

    /** Formato de un día en el idioma dado. */
    public static DateTimeFormatter dayFormat(Language language) {
        return language == Language.EN ? DAY_EN : DAY_ES;
    }

    private static DateTimeFormatter momentFormat(Language language) {
        return language == Language.EN ? MOMENT_EN : MOMENT_ES;
    }

    /** El reporte como bytes de un PDF, con los textos fijos en español. */
    public static byte[] render(IssuesReport report) {
        return render(report, Language.ES);
    }

    /** El reporte como bytes de un PDF, con los textos fijos y las fechas en {@code lang}. */
    public static byte[] render(IssuesReport report, Language lang) {
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 46);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new Footer(PdfText.clean(report.identifier() + " · " + report.projectName()), lang));
            document.addTitle(PdfText.clean(lang.t("Reporte de tareas") + " · " + report.projectName()));
            document.addAuthor(PdfText.clean(report.generatedBy()));
            document.addCreator("TaskFlow");
            document.open();

            document.add(header(writer, report, lang));
            document.add(sectionTitle(lang.t("Filtros aplicados"), lang));
            document.add(filters(report.filters(), lang));
            if (report.truncated()) {
                document.add(note(lang.t("Hay " + report.totalMatching()
                        + " tareas con estos filtros: el reporte trae las primeras " + IssuesReport.MAX_ISSUES
                        + ". Afina los filtros para verlas todas.")));
            }
            if (report.options().includeSummary()) {
                document.add(sectionTitle(lang.t("Resumen"), lang));
                document.add(tiles(report.totals(), lang));
                if (report.totals().total() > 0) {
                    document.add(breakdowns(report, lang));
                }
            }
            if (report.options().includeIssues()) {
                document.add(sectionTitle(lang.t("Tareas") + " · " + groupingText(report.options(), lang), lang));
                if (report.totals().total() == 0) {
                    document.add(note(lang.t("No hay tareas que cumplan estos filtros.")));
                } else {
                    document.add(issues(report.groups(), lang));
                }
            }
            document.close();
        } catch (DocumentException ex) {
            throw new IllegalStateException("No se pudo generar el PDF del reporte", ex);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- encabezado

    private static PdfPTable header(PdfWriter writer, IssuesReport report, Language lang) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[]{46, 470, 254});
        table.setWidthPercentage(100);

        PdfPCell logo = plainCell();
        logo.addElement(logo(writer));
        logo.setPaddingTop(2);
        table.addCell(logo);

        PdfPCell title = plainCell();
        Paragraph heading = new Paragraph(lang.t("Reporte de tareas"), TITLE);
        heading.setLeading(22);
        title.addElement(heading);
        Paragraph project = new Paragraph(
                PdfText.clean(report.projectName() + " (" + report.identifier() + ") · " + report.workspaceName()), SUBTITLE);
        project.setSpacingBefore(3);
        title.addElement(project);
        table.addCell(title);

        PdfPCell meta = plainCell();
        Paragraph brand = new Paragraph("TaskFlow", BRAND);
        brand.setAlignment(Element.ALIGN_RIGHT);
        meta.addElement(brand);
        Paragraph generated = new Paragraph(PdfText.clean(lang.t("Generado el") + " "
                + momentFormat(lang).format(report.generatedAt().atZone(report.zone()))), BODY_MUTED);
        generated.setAlignment(Element.ALIGN_RIGHT);
        generated.setSpacingBefore(2);
        meta.addElement(generated);
        Paragraph author = new Paragraph(PdfText.clean(lang.t("por") + " " + report.generatedBy()), BODY_MUTED);
        author.setAlignment(Element.ALIGN_RIGHT);
        meta.addElement(author);
        table.addCell(meta);

        // Línea dorada bajo el encabezado
        PdfPCell rule = plainCell();
        rule.setColspan(3);
        rule.setFixedHeight(10);
        rule.setBorder(Rectangle.BOTTOM);
        rule.setBorderColorBottom(GOLD);
        rule.setBorderWidthBottom(1.2f);
        table.addCell(rule);
        return table;
    }

    /**
     * El logo de TaskFlow dibujado en vectores (el mismo trazo del SVG de la aplicación: un check que termina en
     * flecha, crema sobre un cuadrado café de esquinas redondeadas), así se ve nítido a cualquier tamaño.
     */
    static Image logo(PdfWriter writer) throws DocumentException {
        PdfTemplate template = writer.getDirectContent().createTemplate(32, 32);
        template.setColorFill(INK);
        template.roundRectangle(0, 0, 32, 32, 9);
        template.fill();
        template.setColorStroke(CREAM);
        template.setLineWidth(3.2f);
        template.setLineCap(PdfContentByte.LINE_CAP_ROUND);
        template.setLineJoin(PdfContentByte.LINE_JOIN_ROUND);
        // SVG: M8.5 16.5 l5 5 L23.5 11  M17.5 11 h6 v6 (el eje Y del PDF va hacia arriba: y = 32 - ySvg)
        template.moveTo(8.5f, 15.5f);
        template.lineTo(13.5f, 10.5f);
        template.lineTo(23.5f, 21f);
        template.moveTo(17.5f, 21f);
        template.lineTo(23.5f, 21f);
        template.lineTo(23.5f, 15f);
        template.stroke();
        Image image = Image.getInstance(template);
        image.scaleAbsolute(34, 34);
        return image;
    }

    // ---------------------------------------------------------------- filtros

    private static PdfPTable filters(List<FilterLine> filters, Language lang) {
        if (filters.isEmpty()) {
            PdfPTable table = new PdfPTable(1);
            table.setWidthPercentage(100);
            PdfPCell cell = boxCell(new Phrase(lang.t("Ninguno: todas las tareas del proyecto."), BODY));
            table.addCell(cell);
            return table;
        }
        // Dos filtros por fila: etiqueta en monoespaciada y, al lado, sus valores (que pasan de línea si son largos)
        PdfPTable table = new PdfPTable(new float[]{78, 307, 78, 307});
        table.setWidthPercentage(100);
        for (FilterLine filter : filters) {
            PdfPCell label = new PdfPCell(new Phrase(PdfText.clean(filter.label()).toUpperCase(lang.locale()), LABEL));
            styleFilterCell(label);
            label.setPaddingLeft(8);
            table.addCell(label);
            PdfPCell value = new PdfPCell(new Phrase(PdfText.clean(filter.value()), BODY));
            styleFilterCell(value);
            table.addCell(value);
        }
        if (filters.size() % 2 == 1) {
            PdfPCell filler = new PdfPCell(new Phrase(""));
            filler.setColspan(2);
            styleFilterCell(filler);
            table.addCell(filler);
        }
        return table;
    }

    private static void styleFilterCell(PdfPCell cell) {
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LINE);
        cell.setBorderWidth(0.6f);
        cell.setBackgroundColor(SUBTLE);
        cell.setPaddingTop(5);
        cell.setPaddingBottom(6);
        cell.setLeading(0, 1.25f);
    }

    // ---------------------------------------------------------------- resumen

    private static PdfPTable tiles(Totals totals, Language lang) {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.addCell(tile(lang.t("Tareas"), totals.total(), NUMBER, lang));
        table.addCell(tile(lang.t("Abiertas"), totals.open(), NUMBER, lang));
        table.addCell(tile(lang.t("Completadas"), totals.completed(), totals.completed() > 0 ? NUMBER_OK : NUMBER, lang));
        table.addCell(tile(lang.t("Canceladas"), totals.cancelled(), NUMBER, lang));
        table.addCell(tile(lang.t("Vencidas"), totals.overdue(), totals.overdue() > 0 ? NUMBER_LATE : NUMBER, lang));
        return table;
    }

    private static PdfPCell tile(String label, int value, Font numberFont, Language lang) {
        PdfPCell cell = plainCell();
        cell.setCellEvent(new RoundedBox());
        cell.setPadding(0);
        cell.setPaddingLeft(13);
        cell.setPaddingTop(9);
        cell.setPaddingBottom(11);
        Paragraph name = new Paragraph(label.toUpperCase(lang.locale()), LABEL);
        cell.addElement(name);
        Paragraph number = new Paragraph(String.valueOf(value), numberFont);
        number.setLeading(22);
        cell.addElement(number);
        return cell;
    }

    private static PdfPTable breakdowns(IssuesReport report, Language lang) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.addCell(breakdown(lang.t("Por estado"), report.byState(), report.totals().total(), true, lang));
        table.addCell(breakdown(lang.t("Por prioridad"), report.byPriority(), report.totals().total(), true, lang));
        // Una tarea con dos asignados cuenta para las dos personas: ahí un porcentaje del total engañaría
        table.addCell(breakdown(lang.t("Por persona"), report.byAssignee(), report.totals().total(), false, lang));
        table.addCell(breakdown(lang.t("Por tipo"), report.byType(), report.totals().total(), true, lang));
        return table;
    }

    /**
     * Un reparto: título y, por fila, nombre y cantidad; con {@code withShare}, también qué parte del total es. Sin
     * barras: con tan pocas filas el número ya lo dice todo.
     */
    private static PdfPCell breakdown(String title, List<Count> counts, int total, boolean withShare, Language lang) {
        PdfPCell panel = plainCell();
        panel.setCellEvent(new RoundedBox());
        panel.setPadding(0);
        panel.setPaddingLeft(13);
        panel.setPaddingRight(13);
        panel.setPaddingTop(9);
        panel.setPaddingBottom(11);
        Paragraph heading = new Paragraph(title, PANEL_TITLE);
        heading.setSpacingAfter(4);
        panel.addElement(heading);

        PdfPTable rows = new PdfPTable(new float[]{96, 70});
        rows.setWidthPercentage(100);
        List<Count> shown = counts.size() > MAX_BREAKDOWN_ROWS ? counts.subList(0, MAX_BREAKDOWN_ROWS - 1) : counts;
        for (Count count : shown) {
            PdfPCell name = plainCell(new Phrase(PdfText.clean(count.name()), BODY));
            name.setPaddingTop(3);
            name.setPaddingBottom(3);
            name.setPaddingRight(6);
            rows.addCell(name);
            Phrase amount = new Phrase(String.valueOf(count.count()), CODE_INK);
            if (withShare && total > 0) {
                amount.add(new Chunk("  " + Math.round(count.count() * 100f / total) + " %", CODE));
            }
            PdfPCell number = plainCell(amount);
            number.setHorizontalAlignment(Element.ALIGN_RIGHT);
            number.setPaddingTop(3);
            rows.addCell(number);
        }
        if (shown.size() < counts.size()) {
            int rest = counts.subList(shown.size(), counts.size()).stream().mapToInt(Count::count).sum();
            PdfPCell more = plainCell(new Phrase(
                    lang.t("y " + (counts.size() - shown.size()) + " más (" + rest + ")"), BODY_MUTED));
            more.setColspan(2);
            more.setPaddingTop(3);
            rows.addCell(more);
        }
        if (counts.isEmpty() || total == 0) {
            PdfPCell empty = plainCell(new Phrase(lang.t("Sin tareas"), BODY_MUTED));
            empty.setColspan(2);
            rows.addCell(empty);
        }
        panel.addElement(rows);
        return panel;
    }

    // ---------------------------------------------------------------- listado

    private static PdfPTable issues(List<Group> groups, Language lang) {
        PdfPTable table = new PdfPTable(ISSUE_COLUMNS);
        table.setWidthPercentage(100);
        // El encabezado de columnas se repite en cada página y una tarea no se parte entre dos páginas
        table.setHeaderRows(1);
        table.setSplitLate(true);
        for (String header : ISSUE_HEADERS) {
            PdfPCell cell = new PdfPCell(new Phrase(lang.t(header).toUpperCase(lang.locale()), LABEL));
            cell.setBackgroundColor(COLUMN);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(6);
            cell.setPaddingTop(5);
            table.addCell(cell);
        }
        for (Group group : groups) {
            if (group.title() != null) {
                int size = group.rows().size();
                Phrase phrase = new Phrase();
                phrase.add(new Chunk(PdfText.clean(group.title()), GROUP));
                phrase.add(new Chunk("   " + size + " " + lang.t(size == 1 ? "tarea" : "tareas"), CODE));
                PdfPCell cell = new PdfPCell(phrase);
                cell.setColspan(ISSUE_COLUMNS.length);
                cell.setBackgroundColor(SUBTLE);
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(LINE);
                cell.setBorderWidth(0.6f);
                cell.setPadding(6);
                cell.setPaddingTop(7);
                table.addCell(cell);
            }
            for (Row row : group.rows()) {
                Font text = row.closed() ? BODY_MUTED : BODY;
                table.addCell(issueCell(new Phrase(PdfText.clean(row.code()), CODE)));
                table.addCell(issueCell(new Phrase(PdfText.clean(row.type()), BODY_MUTED)));
                table.addCell(issueCell(new Phrase(PdfText.clean(row.title()), row.closed() ? BODY_MUTED : BODY_BOLD)));
                table.addCell(issueCell(new Phrase(PdfText.clean(row.state()), text)));
                table.addCell(issueCell(new Phrase(PdfText.clean(row.priority()), text)));
                table.addCell(issueCell(new Phrase(listText(row.assignees()), text)));
                table.addCell(issueCell(new Phrase(listText(row.labels()), BODY_MUTED)));
                table.addCell(issueCell(row.dueDate() == null
                        ? new Phrase("—", BODY_MUTED)
                        : new Phrase(dayFormat(lang).format(row.dueDate()), row.overdue() ? BODY_LATE : text)));
            }
        }
        return table;
    }

    private static PdfPCell issueCell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LINE);
        cell.setBorderWidth(0.6f);
        cell.setPadding(6);
        cell.setPaddingTop(5);
        cell.setPaddingBottom(7);
        cell.setLeading(0, 1.25f);
        return cell;
    }

    private static String listText(List<String> values) {
        return values == null || values.isEmpty() ? "—" : PdfText.clean(String.join(", ", values));
    }

    static String groupingText(ReportOptions options, Language lang) {
        String group = switch (options.groupByOrDefault()) {
            case NONE -> "sin agrupar";
            case STATE -> "por estado";
            case PRIORITY -> "por prioridad";
            case ASSIGNEE -> "por persona";
            case TYPE -> "por tipo";
        };
        String sort = switch (options.sortOrDefault()) {
            case PRIORITY -> "prioridad";
            case NEWEST -> "más recientes";
            case OLDEST -> "más antiguas";
            case UPDATED -> "actualizadas recientemente";
            case DUE_DATE -> "fecha límite";
            case TITLE -> "título";
            case MANUAL -> "el del tablero";
        };
        return lang.t(group) + ", " + lang.t("orden") + ": " + lang.t(sort);
    }

    // ---------------------------------------------------------------- piezas comunes

    private static Paragraph sectionTitle(String text, Language lang) {
        Paragraph paragraph = new Paragraph(PdfText.clean("// " + text).toUpperCase(lang.locale()), LABEL);
        paragraph.setSpacingBefore(14);
        paragraph.setSpacingAfter(6);
        return paragraph;
    }

    private static Paragraph note(String text) {
        Paragraph paragraph = new Paragraph(PdfText.clean(text), BODY_MUTED);
        paragraph.setSpacingBefore(6);
        return paragraph;
    }

    private static PdfPCell plainCell() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPCell plainCell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPCell boxCell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setBackgroundColor(SUBTLE);
        cell.setPadding(8);
        return cell;
    }

    /** Recuadro redondeado dentro de la celda, con un margen: así las celdas vecinas quedan separadas. */
    private static final class RoundedBox implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte canvas = canvases[PdfPTable.BACKGROUNDCANVAS];
            canvas.saveState();
            canvas.setColorStroke(LINE);
            canvas.setLineWidth(0.8f);
            canvas.roundRectangle(position.getLeft() + 3, position.getBottom() + 3,
                    position.getWidth() - 6, position.getHeight() - 6, 7);
            canvas.stroke();
            canvas.restoreState();
        }
    }

    /** Pie de cada página: a la izquierda de qué es el reporte y a la derecha «Página N de M». */
    private static final class Footer extends PdfPageEventHelper {
        private final String project;
        private final Language lang;
        private PdfTemplate total;
        private BaseFont font;

        private Footer(String project, Language lang) {
            this.project = project;
            this.lang = lang;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            try {
                font = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception ex) {
                throw new IllegalStateException("No se pudo cargar la fuente del pie de página", ex);
            }
            total = writer.getDirectContent().createTemplate(40, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            float y = document.bottom() - 18;
            canvas.saveState();
            canvas.setColorStroke(LINE);
            canvas.setLineWidth(0.6f);
            canvas.moveTo(document.left(), y + 11);
            canvas.lineTo(document.right(), y + 11);
            canvas.stroke();
            canvas.setColorFill(MUTED);
            canvas.beginText();
            canvas.setFontAndSize(font, 8);
            canvas.showTextAligned(Element.ALIGN_LEFT, "TaskFlow · " + lang.t("Reporte de tareas") + " · " + project,
                    document.left(), y, 0);
            String page = lang.t("Página " + writer.getPageNumber() + " de ");
            float totalWidth = font.getWidthPoint("000", 8);
            canvas.showTextAligned(Element.ALIGN_RIGHT, page, document.right() - totalWidth, y, 0);
            canvas.endText();
            canvas.addTemplate(total, document.right() - totalWidth, y);
            canvas.restoreState();
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(font, 8);
            total.setColorFill(MUTED);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }
    }
}
