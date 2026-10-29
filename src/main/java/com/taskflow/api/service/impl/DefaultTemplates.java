package com.taskflow.api.service.impl;

import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.TemplateChecklistItem;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;

import java.util.List;

/**
 * Plantillas con las que nace cada proyecto. Son los mismos textos que la migración
 * {@code V11__issue_templates.sql} insertó en los proyectos que ya existían; los esqueletos de la descripción son
 * los de {@code V22__template_skeletons.sql}, que actualizó los que nadie había cambiado. Cada una fija el tipo de la
 * tarea que crea ({@code V24__issue_types.sql}): «Reporte de bug» un bug, «Historia de usuario» una historia y «Tarea
 * técnica» una tarea (sin plantilla, también tarea).
 */
final class DefaultTemplates {

    static final String BUG_REPORT = "Reporte de bug";
    static final String USER_STORY = "Historia de usuario";
    static final String TECHNICAL_TASK = "Tarea técnica";

    /*
     * Esqueletos de la descripción, en Markdown: solo la estructura, sin texto de relleno. La historia de usuario es
     * una cita con «**Como** … **quiero** … **para** …», que el frontend dibuja como la tarjeta de la historia.
     */
    static final String BUG_REPORT_DESCRIPTION = """
            ## Pasos para reproducir
            1.

            ## Resultado esperado

            ## Resultado actual

            ## Entorno
            - Navegador:
            - Sistema operativo:
            - Versión:""";
    static final String USER_STORY_DESCRIPTION = """
            > **Como**
            > **quiero**
            > **para**""";
    static final String TECHNICAL_TASK_DESCRIPTION = """
            ## Objetivo

            ## Detalles técnicos""";

    private DefaultTemplates() {
    }

    static List<IssueTemplate> forProject(Project project) {
        return List.of(
                template(project, 0, BUG_REPORT, IssueType.BUG, "[Bug] ", IssuePriority.HIGH, BUG_REPORT_DESCRIPTION,
                        criterion("Dado los pasos, cuando se repiten, entonces el error ya no ocurre"),
                        check("Reproducir el error"),
                        check("Escribir la prueba"),
                        check("Corregir"),
                        check("Probar en otro navegador")),
                template(project, 1, USER_STORY, IssueType.STORY, null, null, USER_STORY_DESCRIPTION,
                        criterion("Dado que soy un usuario registrado, cuando inicio sesión, "
                                + "entonces veo mi panel principal"),
                        criterion("Dado que dejo un campo obligatorio vacío, cuando envío el formulario, "
                                + "entonces veo un mensaje de error")),
                template(project, 2, TECHNICAL_TASK, IssueType.TASK, null, null, TECHNICAL_TASK_DESCRIPTION,
                        check("Implementar"),
                        check("Pruebas"),
                        check("Revisión de código")));
    }

    private static IssueTemplate template(Project project, int position, String name, IssueType type,
                                          String titlePrefix, IssuePriority priority, String description,
                                          TemplateChecklistItem... checklist) {
        IssueTemplate template = new IssueTemplate();
        template.setProject(project);
        template.setPosition(position);
        template.setName(name);
        template.setType(type);
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
