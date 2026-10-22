package com.taskflow.api.service;

import com.taskflow.api.dto.request.CommentRequest;
import com.taskflow.api.dto.response.CommentResponse;

import java.util.List;
import java.util.UUID;

public interface CommentService {

    List<CommentResponse> getComments(String slug, UUID projectId, UUID issueId);

    CommentResponse create(String slug, UUID projectId, UUID issueId, UUID userId, CommentRequest request);

    /** Solo el autor puede editar su comentario. */
    CommentResponse update(String slug, UUID projectId, UUID issueId, UUID commentId, UUID userId,
                           CommentRequest request);

    /** El autor o un admin del workspace pueden borrar el comentario. */
    void delete(String slug, UUID projectId, UUID issueId, UUID commentId, UUID userId);
}
