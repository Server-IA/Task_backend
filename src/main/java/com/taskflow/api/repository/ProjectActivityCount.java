package com.taskflow.api.repository;

import java.util.UUID;

/**
 * Cantidad de entradas del historial de un proyecto.
 */
public record ProjectActivityCount(UUID projectId, String name, String identifier, long count) {
}
