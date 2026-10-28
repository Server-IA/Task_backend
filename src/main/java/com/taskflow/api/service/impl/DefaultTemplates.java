package com.taskflow.api.service.impl;

import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.TemplateChecklistItem;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.IssuePriority;

import java.util.List;

/**
 * Plantillas con las que nace cada proyecto. Son los mismos textos que la migración
 * {@code V11__issue_templates.sql} insertó en los proyectos que ya existían.
 */
final class DefaultTemplates {

    static final String BUG_REPORT = "Reporte de bug";
    static final String USER_STORY = "Historia de usuario";
    static final String TECHNICAL_TASK = "Tarea técnica";

    private DefaultTemplates() {
    }

    static List<IssueTemplate> forProject(Project project) {
        return List.of(
                template(project, 0, BUG_REPORT, "[Bug] ", IssuePriority.HIGH, """
                                ## Pasos para reproducir
                                1. …
                                2. …

                                ## Resultado esperado
                                …

                                ## Resultado actual
                                …

                                ## Entorno
                                - Navegador: …
                                - Sistema operativo: …
                                - Versión: …""",
                        criterion("Dado los pasos, cuando se repiten, entonces el error ya no ocurre"),
                        check("Reproducir el error"),
                        check("Escribir la prueba"),
                        check("Corregir"),
                        check("Probar en otro navegador")),
                template(project, 1, USER_STORY, null, null, "Como … quiero … para …",
                        criterion("Dado que soy un usuario registrado, cuando inicio sesión, "
                                + "entonces veo mi panel principal"),
                        criterion("Dado que dejo un campo obligatorio vacío, cuando envío el formulario, "
                                + "entonces veo un mensaje de error")),
                template(project, 2, TECHNICAL_TASK, null, null, """
                                ## Objetivo
                                …

                                ## Detalles técnicos
                                …""",
                        check("Implementar"),
                        check("Pruebas"),
                        check("Revisión de código")));
    }

    private static IssueTemplate template(Project project, int position, String name, String titlePrefix,
                                          IssuePriority priority, String description,
                                          TemplateChecklistItem... checklist) {
        IssueTemplate template = new IssueTemplate();
        template.setProject(project);
        template.setPosition(position);
        template.setName(name);
        template.setTitlePrefix(titlePrefix);
        template.setPriority(priority);
        template.setDescription(description);
        template.getChecklist().addAll(List.of(checklist));
        return template;
    }

    private static TemplateChecklistItem criterion(String text) {
        return new TemplateChecklistItem(ChecklistItemKind.CRITERION, text);
    }

    private static TemplateChecklistItem check(String text) {
        return new TemplateChecklistItem(ChecklistItemKind.CHECK, text);
    }
}
