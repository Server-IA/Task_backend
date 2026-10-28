package com.taskflow.api.service;

import com.taskflow.api.dto.request.CloseCycleRequest;
import com.taskflow.api.dto.request.CycleRequest;
import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.UpdateCycleRequest;
import com.taskflow.api.dto.response.CloseCyclePreviewResponse;
import com.taskflow.api.dto.response.CloseCycleResponse;
import com.taskflow.api.dto.response.CycleResponse;

import java.util.List;
import java.util.UUID;

public interface CycleService {

    List<CycleResponse> getCycles(String slug, UUID projectId);

    CycleResponse create(String slug, UUID projectId, CycleRequest request);

    CycleResponse get(String slug, UUID projectId, UUID cycleId);

    /** En un ciclo cerrado solo se puede cambiar la descripción: cambiar el nombre o las fechas responde 409. */
    CycleResponse update(String slug, UUID projectId, UUID cycleId, UpdateCycleRequest request);

    void delete(String slug, UUID projectId, UUID cycleId);

    /** Agrega issues al ciclo; si alguno estaba en otro ciclo, se mueve a este. Un ciclo cerrado responde 409. */
    CycleResponse addIssues(String slug, UUID projectId, UUID cycleId, UUID userId, IssueIdsRequest request);

    CycleResponse removeIssue(String slug, UUID projectId, UUID cycleId, UUID issueId, UUID userId);

    /** Tareas completadas y sin terminar del ciclo, su cycle time y el siguiente ciclo (destino por defecto). */
    CloseCyclePreviewResponse closePreview(String slug, UUID projectId, UUID cycleId);

    /**
     * Cierra el ciclo: mueve sus tareas sin terminar al ciclo destino o fuera del ciclo, acorta su fecha de fin a hoy
     * si terminaba después y, si se pide, abre la retrospectiva. Un ciclo ya cerrado o que aún no empezó responde 409.
     */
    CloseCycleResponse close(String slug, UUID projectId, UUID cycleId, UUID userId, CloseCycleRequest request);
}
