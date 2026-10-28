package com.taskflow.api.service;

import com.taskflow.api.dto.response.IssueResponse;

import java.util.List;
import java.util.UUID;

public interface SearchService {

    /**
     * Tareas de los proyectos accesibles cuyo título o código coincide con {@code query}: primero la de código
     * exacto (por ejemplo «WEB-12») y luego las actualizadas más recientemente.
     */
    List<IssueResponse> searchIssues(String slug, UUID userId, String query, int limit);
}
