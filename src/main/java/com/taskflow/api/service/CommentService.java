package com.taskflow.api.service;

import com.taskflow.api.dto.request.CommentRequest;
import com.taskflow.api.dto.response.CommentResponse;

import java.util.List;
import java.util.UUID;

public interface CommentService {

    List<CommentResponse> getComments(String slug, UUID projectId, UUID issueId);

    /**
     * Notifica a los mencionados con {@code @[Nombre](id)} que pueden ver la tarea ({@code ISSUE_MENTIONED}) y a los
     * asignados y al creador ({@code ISSUE_COMMENTED}); quien está mencionado recibe solo la mención.
     */
    CommentResponse create(String slug, UUID projectId, UUID issueId, UUID userId, CommentRequest request);

    /** Solo el autor puede editar su comentario. Notifica únicamente a los mencionados por primera vez. */
    CommentResponse update(String slug, UUID projectId, UUID issueId, UUID commentId, UUID userId,
                           CommentRequest request);

    /** El autor o un project manager del workspace pueden borrar el comentario. */
    void delete(String slug, UUID projectId, UUID issueId, UUID commentId, UUID userId);
}
