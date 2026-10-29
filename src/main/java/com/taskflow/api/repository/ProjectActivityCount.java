package com.taskflow.api.repository;

import com.taskflow.api.entity.enums.ProjectColor;

import java.util.UUID;

/**
 * Cantidad de entradas del historial de un proyecto.
 */
public record ProjectActivityCount(UUID projectId, String name, String identifier, ProjectColor color, long count) {
}
