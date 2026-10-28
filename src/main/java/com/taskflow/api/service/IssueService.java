package com.taskflow.api.service;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface IssueService {

    PageResponse<IssueResponse> getIssues(String slug, UUID projectId, IssueFilter filter, Pageable pageable);

    /** Asigna el siguiente número del proyecto (WEB-12) y deja el issue al final de su columna. */
    IssueResponse create(String slug, UUID projectId, UUID userId, CreateIssueRequest request);

    IssueResponse get(String slug, UUID projectId, UUID issueId);

    IssueResponse update(String slug, UUID projectId, UUID issueId, UUID userId, UpdateIssueRequest request);

    /** Cambia el estado y la posición en el Kanban actualizando solo la fila del issue. */
    IssueResponse move(String slug, UUID projectId, UUID issueId, UUID userId, MoveIssueRequest request);

    /** Solo el project manager o quien creó la tarea pueden eliminarla (403 si no). */
    void delete(String slug, UUID projectId, UUID issueId, UUID userId);

    List<IssueResponse> getSubIssues(String slug, UUID projectId, UUID issueId);
}
