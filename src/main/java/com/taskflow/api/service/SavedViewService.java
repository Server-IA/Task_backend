package com.taskflow.api.service;

import com.taskflow.api.dto.request.CreateSavedViewRequest;
import com.taskflow.api.dto.request.ReorderSavedViewsRequest;
import com.taskflow.api.dto.request.UpdateSavedViewRequest;
import com.taskflow.api.dto.response.SavedViewResponse;

import java.util.List;
import java.util.UUID;

/**
 * Vistas guardadas de un proyecto. Las personales solo las ve y gestiona su dueño (para los demás no existen: 404);
 * las compartidas las ve todo el proyecto, pero solo el project manager las crea, edita, elimina o comparte (403).
 */
public interface SavedViewService {

    /** Primero las compartidas y luego las personales del usuario, cada grupo en su orden. */
    List<SavedViewResponse> getViews(String slug, UUID projectId, UUID userId);

    SavedViewResponse create(String slug, UUID projectId, UUID userId, CreateSavedViewRequest request);

    SavedViewResponse update(String slug, UUID projectId, UUID viewId, UUID userId, UpdateSavedViewRequest request);

    void delete(String slug, UUID projectId, UUID viewId, UUID userId);

    /**
     * Reordena las compartidas (solo el project manager) y/o mis vistas personales; cada lista debe tener exactamente
     * las vistas de su grupo. Devuelve las vistas visibles, como {@link #getViews}.
     */
    List<SavedViewResponse> reorder(String slug, UUID projectId, UUID userId, ReorderSavedViewsRequest request);
}
