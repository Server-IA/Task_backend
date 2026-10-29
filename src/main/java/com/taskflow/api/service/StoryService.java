package com.taskflow.api.service;

import com.taskflow.api.dto.response.StoryResponse;

import java.util.List;
import java.util.UUID;

public interface StoryService {

    /**
     * Historias del proyecto (abiertas y cerradas, de la más nueva a la más vieja) con su progreso, las personas de sus
     * tareas, sus tareas y bugs y las tareas relacionadas con ellas. Las pestañas (abiertas, sin partir, para revisar,
     * cerradas) y los filtros se arman con esto en el cliente. Se lee con pocas consultas, sin una por historia.
     */
    List<StoryResponse> getStories(String slug, UUID projectId);
}
