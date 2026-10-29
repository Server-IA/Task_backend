package com.taskflow.api.service.impl;

import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.TemplateChecklistItem;
import com.taskflow.api.entity.enums.IssuePriority;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.taskflow.api.entity.enums.ChecklistItemKind.CHECK;
import static com.taskflow.api.entity.enums.ChecklistItemKind.CRITERION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Plantillas con las que nace cada proyecto, y que coinciden con las que la migración V11 dio a los existentes.
 */
class DefaultTemplatesTest {

    private final Project project = new Project();
    private final List<IssueTemplate> templates = DefaultTemplates.forProject(project);

    @Test
    void sonTresEnOrdenYSinEtiquetas() {
        assertThat(templates)
                .extracting(IssueTemplate::getName, IssueTemplate::getPosition, IssueTemplate::getTitlePrefix,
                        IssueTemplate::getPriority)
                .containsExactly(
                        tuple("Reporte de bug", 0, "[Bug] ", IssuePriority.HIGH),
                        tuple("Historia de usuario", 1, null, null),
                        tuple("Tarea técnica", 2, null, null));
        assertThat(templates).allSatisfy(template -> {
            assertThat(template.getProject()).isSameAs(project);
            assertThat(template.getLabels()).isEmpty();
        });
    }

    @Test
    void reporteDeBug() {
        IssueTemplate bug = templates.get(0);

        assertThat(bug.getDescription()).contains("## Pasos para reproducir", "## Resultado esperado",
                "## Resultado actual", "## Entorno");
        assertThat(bug.getChecklist())
                .extracting(TemplateChecklistItem::getKind, TemplateChecklistItem::getText)
                .containsExactly(
                        tuple(CRITERION, "Dado los pasos, cuando se repiten, entonces el error ya no ocurre"),
                        tuple(CHECK, "Reproducir el error"),
                        tuple(CHECK, "Escribir la prueba"),
                        tuple(CHECK, "Corregir"),
                        tuple(CHECK, "Probar en otro navegador"));
    }

    @Test
    void historiaDeUsuario() {
        IssueTemplate story = templates.get(1);

        // La cita «**Como** … **quiero** … **para** …» que el frontend dibuja como la tarjeta de la historia
        assertThat(story.getDescription()).isEqualTo("> **Como**\n> **quiero**\n> **para**");
        assertThat(story.getChecklist()).hasSize(2).allSatisfy(item -> {
            assertThat(item.getKind()).isEqualTo(CRITERION);
            assertThat(item.getText()).startsWith("Dado").contains(", cuando ", ", entonces ");
        });
    }

    @Test
    void tareaTecnica() {
        IssueTemplate tech = templates.get(2);

        assertThat(tech.getDescription()).contains("## Objetivo", "## Detalles técnicos");
        assertThat(tech.getChecklist())
                .extracting(TemplateChecklistItem::getKind, TemplateChecklistItem::getText)
                .containsExactly(tuple(CHECK, "Implementar"), tuple(CHECK, "Pruebas"), tuple(CHECK, "Revisión de código"));
    }

    @Test
    void losEsqueletosNoTienenTextoDeRelleno() {
        assertThat(templates).allSatisfy(template -> assertThat(template.getDescription()).doesNotContain("…"));
    }

    /** Las migraciones repiten los textos en SQL (con los saltos de línea escritos como \n). */
    @Test
    void lasMigracionesUsanLosMismosTextos() throws IOException {
        String v11 = migration("V11__issue_templates.sql");
        String v22 = migration("V22__template_skeletons.sql");
        for (IssueTemplate template : templates) {
            assertThat(v11).contains("'" + template.getName() + "'");
            template.getChecklist().forEach(item -> assertThat(v11).contains("'" + item.getText() + "'"));
            assertThat(v22).contains("'" + template.getDescription().replace("\n", "\\n") + "'");
        }
        assertThat(v11).contains("'[Bug] '");
    }

    /** V22 solo cambia los esqueletos que siguen exactamente como los dejó V11. */
    @Test
    void v22SoloCambiaLosEsqueletosDeV11() throws IOException {
        String v11 = migration("V11__issue_templates.sql");
        String v22 = migration("V22__template_skeletons.sql");
        Matcher where = Pattern.compile("WHERE description = (E?'[^;]*');").matcher(v22);
        int count = 0;
        while (where.find()) {
            assertThat(v11).contains(where.group(1).replaceFirst("^E", ""));
            count++;
        }
        assertThat(count).isEqualTo(templates.size());
    }

    private String migration(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/db/migration/" + name)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
