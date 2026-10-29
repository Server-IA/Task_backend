package com.taskflow.api.service;

import com.taskflow.api.dto.response.IssueResponse;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SearchService {

    /**
     * Tareas de los proyectos accesibles cuyo título o código coincide con {@code query}: primero la de código
     * exacto (por ejemplo «WEB-12») y luego las actualizadas más recientemente.
     */
    List<IssueResponse> searchIssues(String slug, UUID userId, String query, int limit);

    /**
     * Tareas de los proyectos accesibles con alguno de esos códigos (por ejemplo «WEB-12»), para dibujar las
     * referencias de una descripción o un comentario. Los códigos inválidos o de tareas que no existen (o que no
     * puedo ver) no aparecen. Como mucho {@code MAX_CODES} códigos distintos.
     */
    List<IssueResponse> findByCodes(String slug, UUID userId, Collection<String> codes);
}
