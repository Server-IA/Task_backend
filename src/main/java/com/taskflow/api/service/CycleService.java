package com.taskflow.api.service;

import com.taskflow.api.dto.request.CycleRequest;
import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.UpdateCycleRequest;
import com.taskflow.api.dto.response.CycleResponse;

import java.util.List;
import java.util.UUID;

public interface CycleService {

    List<CycleResponse> getCycles(String slug, UUID projectId);

    CycleResponse create(String slug, UUID projectId, CycleRequest request);

    CycleResponse get(String slug, UUID projectId, UUID cycleId);

    CycleResponse update(String slug, UUID projectId, UUID cycleId, UpdateCycleRequest request);

    void delete(String slug, UUID projectId, UUID cycleId);

    /** Agrega issues al ciclo; si alguno estaba en otro ciclo, se mueve a este. */
    CycleResponse addIssues(String slug, UUID projectId, UUID cycleId, UUID userId, IssueIdsRequest request);

    CycleResponse removeIssue(String slug, UUID projectId, UUID cycleId, UUID issueId, UUID userId);
}
