package com.taskflow.api.service;

import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.UpdateChecklistItemRequest;
import com.taskflow.api.dto.response.ChecklistItemResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueChecklistItem;

import java.util.List;
import java.util.UUID;

public interface IssueChecklistService {

    /** Criterios de aceptación primero y luego los pasos de la checklist, cada grupo en su orden. */
    List<ChecklistItemResponse> getItems(String slug, UUID projectId, UUID issueId);

    /** Agrega el elemento al final de los de su tipo. */
    ChecklistItemResponse create(String slug, UUID projectId, UUID issueId, ChecklistItemRequest request);

    /**
     * Edita el texto, lo marca o desmarca, o lo mueve a otra posición dentro de los de su tipo. Al marcarlo guarda
     * quién ({@code userId}) y cuándo; al desmarcarlo los borra.
     */
    ChecklistItemResponse update(String slug, UUID projectId, UUID issueId, UUID itemId, UUID userId,
                                 UpdateChecklistItemRequest request);

    void delete(String slug, UUID projectId, UUID issueId, UUID itemId);

    /** Crea los elementos de una tarea nueva en el orden recibido. Lo usa {@code IssueService} al crear la tarea. */
    List<IssueChecklistItem> createAll(Issue issue, List<ChecklistItemRequest> items);
}
