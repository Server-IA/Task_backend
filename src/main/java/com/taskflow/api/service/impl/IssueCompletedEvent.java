package com.taskflow.api.service.impl;

import java.util.UUID;

/**
 * Una tarea acaba de pasar a un estado completado (la movió una persona o una automatización). Lo escucha quien tiene
 * algo que hacer cuando eso pasa: las reglas de GitHub Actions del proyecto.
 */
public record IssueCompletedEvent(UUID issueId, UUID projectId) {
}
