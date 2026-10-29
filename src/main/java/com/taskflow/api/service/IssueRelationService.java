package com.taskflow.api.service;

import com.taskflow.api.dto.request.CreateIssueRelationRequest;
import com.taskflow.api.dto.response.IssueRelationResponse;
import com.taskflow.api.dto.response.IssueRelationsResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;

import java.util.UUID;

public interface IssueRelationService {

    /**
     * Dependencias de la tarea. Las que cruzan a un proyecto al que el usuario no tiene acceso se devuelven sin título
     * ni asignados ({@code accessible: false}).
     */
    IssueRelationsResponse getRelations(String slug, UUID projectId, UUID issueId, UUID userId);

    /**
     * Relaciona la tarea con otra del mismo workspace a cuyo proyecto el usuario tiene acceso ({@code BLOCKED_BY} se
     * guarda como el {@code BLOCKS} inverso).
     * Responde 409 si es la misma tarea, si la relación ya existe o si dos tareas quedarían bloqueándose
     * mutuamente. Registra la relación en el historial de ambas tareas.
     */
    IssueRelationResponse create(String slug, UUID projectId, UUID issueId, UUID userId,
                                 CreateIssueRelationRequest request);

    /** Quita una relación en la que participa la tarea (404 si no participa). */
    void delete(String slug, UUID projectId, UUID issueId, UUID relationId, UUID userId);

    /**
     * Lo llama {@code IssueService} cuando una tarea abierta pasa al grupo COMPLETED: avisa a los asignados de cada
     * tarea abierta que bloqueaba y que ya no tiene otros bloqueos abiertos.
     */
    void notifyUnblocked(Issue completedBlocker, User actor);

    /**
     * El estado pasó de un grupo abierto a COMPLETED: avisa una vez a cada tarea que con eso dejó de estar bloqueada
     * (aunque la bloquearan varias tareas del estado), con una consulta para todo el estado.
     */
    void notifyUnblockedByState(State state, User actor);
}
