package com.taskflow.api.service;

import com.taskflow.api.dto.request.CreateStateRequest;
import com.taskflow.api.dto.request.ReorderStatesRequest;
import com.taskflow.api.dto.request.UpdateStateRequest;
import com.taskflow.api.dto.response.StateResponse;

import java.util.List;
import java.util.UUID;

public interface StateService {

    List<StateResponse> getStates(String slug, UUID projectId);

    StateResponse create(String slug, UUID projectId, CreateStateRequest request);

    StateResponse update(String slug, UUID projectId, UUID stateId, UUID userId, UpdateStateRequest request);

    /** Falla si es el último estado del proyecto o si todavía tiene issues. */
    void delete(String slug, UUID projectId, UUID stateId);

    /**
     * Reordena las columnas: {@code stateIds} debe tener exactamente los estados del proyecto, sin repetir. Cada uno
     * queda en la posición de su índice. Devuelve los estados en el orden nuevo.
     */
    List<StateResponse> reorder(String slug, UUID projectId, ReorderStatesRequest request);
}
