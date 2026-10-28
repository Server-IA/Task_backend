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

        assertThat(story.getDescription()).isEqualTo("Como … quiero … para …");
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

    /** La migración repite los textos en SQL (con los saltos de línea escritos como \n). */
    @Test
    void laMigracionUsaLosMismosTextos() throws IOException {
        String migration;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V11__issue_templates.sql")) {
            assertThat(in).isNotNull();
            migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (IssueTemplate template : templates) {
            assertThat(migration)
                    .contains("'" + template.getName() + "'")
                    .contains("'" + template.getDescription().replace("\n", "\\n") + "'");
            template.getChecklist().forEach(item -> assertThat(migration).contains("'" + item.getText() + "'"));
        }
        assertThat(migration).contains("'[Bug] '");
    }
}
