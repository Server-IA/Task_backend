package com.taskflow.api.controller;

import com.taskflow.api.dto.request.CommentRequest;
import com.taskflow.api.dto.response.CommentResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Comentarios", description = "Comentarios de un issue")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/issues/{issueId}/comments")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @Operation(summary = "Listar comentarios (del más antiguo al más reciente)")
    @GetMapping
    public List<CommentResponse> getComments(@PathVariable String slug, @PathVariable UUID projectId,
                                             @PathVariable UUID issueId) {
        return commentService.getComments(slug, projectId, issueId);
    }

    @Operation(summary = "Comentar",
            description = "Notifica a los asignados y al creador de la tarea. Las menciones se escriben en el cuerpo "
                    + "como @[Nombre Visible](id-del-usuario): se avisa (ISSUE_MENTIONED) a los mencionados que son "
                    + "miembros del proyecto o project managers, y a ellos no les llega además el aviso del comentario.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                                  @AuthenticationPrincipal UserPrincipal me,
                                  @Valid @RequestBody CommentRequest request) {
        return commentService.create(slug, projectId, issueId, me.getId(), request);
    }

    @Operation(summary = "Editar comentario (solo el autor)",
            description = "Solo se avisa a quienes se mencionan por primera vez en el comentario.")
    @PatchMapping("/{commentId}")
    public CommentResponse update(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                                  @PathVariable UUID commentId, @AuthenticationPrincipal UserPrincipal me,
                                  @Valid @RequestBody CommentRequest request) {
        return commentService.update(slug, projectId, issueId, commentId, me.getId(), request);
    }

    @Operation(summary = "Borrar comentario (autor o project manager)")
    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                       @PathVariable UUID commentId, @AuthenticationPrincipal UserPrincipal me) {
        commentService.delete(slug, projectId, issueId, commentId, me.getId());
    }
}
