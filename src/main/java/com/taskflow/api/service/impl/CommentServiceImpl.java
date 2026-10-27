package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CommentRequest;
import com.taskflow.api.dto.response.CommentResponse;
import com.taskflow.api.entity.Comment;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.CommentRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.CommentService;
import com.taskflow.api.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** Extracto del comentario que se guarda en el historial. */
    private static final int ACTIVITY_EXCERPT_LENGTH = 200;

    private final CommentRepository commentRepository;
    private final IssueRepository issueRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final ResourceLookup lookup;
    private final CollaborationMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(String slug, UUID projectId, UUID issueId) {
        Issue issue = findIssue(slug, projectId, issueId);
        return commentRepository.findAllByIssueIdWithAuthor(issue.getId()).stream()
                .map(mapper::toCommentResponse)
                .toList();
    }

    @Override
    @Transactional
    public CommentResponse create(String slug, UUID projectId, UUID issueId, UUID userId, CommentRequest request) {
        Issue issue = findIssue(slug, projectId, issueId);
        User author = lookup.user(userId);

        Comment comment = new Comment();
        comment.setIssue(issue);
        comment.setAuthor(author);
        comment.setBody(request.body().trim());
        commentRepository.save(comment);

        activityService.record(issue, author, ActivityField.COMMENT, null, excerpt(comment.getBody()));
        notificationService.notifyCommented(issue, author);
        return mapper.toCommentResponse(comment);
    }

    @Override
    @Transactional
    public CommentResponse update(String slug, UUID projectId, UUID issueId, UUID commentId, UUID userId,
                                  CommentRequest request) {
        Comment comment = findComment(findIssue(slug, projectId, issueId), commentId);
        if (!comment.getAuthor().getId().equals(userId)) {
            throw new ForbiddenException("Solo el autor puede editar el comentario");
        }
        comment.setBody(request.body().trim());
        return mapper.toCommentResponse(commentRepository.saveAndFlush(comment));
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID issueId, UUID commentId, UUID userId) {
        Comment comment = findComment(findIssue(slug, projectId, issueId), commentId);
        boolean isAuthor = comment.getAuthor().getId().equals(userId);
        boolean isAdmin = workspaceMemberRepository.findRole(slug, userId)
                .map(WorkspaceRole::isAdmin)
                .orElse(false);
        if (!isAuthor && !isAdmin) {
            throw new ForbiddenException("Solo el autor o un administrador pueden borrar el comentario");
        }
        commentRepository.delete(comment);
    }

    private Issue findIssue(String slug, UUID projectId, UUID issueId) {
        Project project = lookup.project(slug, projectId);
        return issueRepository.findByIdAndProjectId(issueId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
    }

    private Comment findComment(Issue issue, UUID commentId) {
        return commentRepository.findByIdAndIssueId(commentId, issue.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Comentario no encontrado"));
    }

    private static String excerpt(String body) {
        return body.length() <= ACTIVITY_EXCERPT_LENGTH ? body : body.substring(0, ACTIVITY_EXCERPT_LENGTH) + "…";
    }
}
