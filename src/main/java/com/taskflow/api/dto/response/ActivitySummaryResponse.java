package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ProjectColor;

import java.util.List;
import java.util.UUID;

/**
 * Resumen de la actividad de los últimos días en los proyectos a los que el usuario tiene acceso.
 *
 * @param changes   entradas del historial (de cualquier tipo, comentarios incluidos)
 * @param comments  comentarios
 * @param completed tareas completadas
 * @param created   tareas creadas
 * @param byProject entradas del historial por proyecto, de más a menos (solo proyectos con actividad)
 */
public record ActivitySummaryResponse(
        long changes,
        long comments,
        long completed,
        long created,
        List<ProjectActivity> byProject
) {

    public record ProjectActivity(UUID projectId, String name, String identifier, ProjectColor color, long count) {
    }
}
