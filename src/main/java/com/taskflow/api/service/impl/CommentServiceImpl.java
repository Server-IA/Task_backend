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
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.CommentService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.util.MentionParser;
import com.taskflow.api.util.TextCut;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** Extracto del comentario que se guarda en el historial (lo muestra el feed de actividad). */
    static final int ACTIVITY_EXCERPT_LENGTH = 240;

    private final CommentRepository commentRepository;
    private final IssueRepository issueRepository;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final ResourceLookup lookup;
    private final CollaborationMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(String slug, UUID projectId, UUID issueId) {
        Issue issue = findIssue(lookup.project(slug, projectId), issueId);
        return commentRepository.findAllByIssueIdWithAuthor(issue.getId()).stream()
                .map(mapper::toCommentResponse)
                .toList();
    }

    @Override
    @Transactional
    public CommentResponse create(String slug, UUID projectId, UUID issueId, UUID userId, CommentRequest request) {
        Project project = lookup.project(slug, projectId);
        Issue issue = findIssue(project, issueId);
        User author = lookup.user(userId);

        Comment comment = new Comment();
        comment.setIssue(issue);
        comment.setAuthor(author);
        comment.setBody(request.body().trim());
        commentRepository.save(comment);

        activityService.record(issue, author, ActivityField.COMMENT, null, excerpt(comment.getBody()));
        List<User> mentioned = mentionable(project, author, MentionParser.userIds(comment.getBody()));
        notificationService.notifyCommented(issue, author, comment, mentioned);
        return mapper.toCommentResponse(comment);
    }

    @Override
    @Transactional
    public CommentResponse update(String slug, UUID projectId, UUID issueId, UUID commentId, UUID userId,
                                  CommentRequest request) {
        Project project = lookup.project(slug, projectId);
        Issue issue = findIssue(project, issueId);
        Comment comment = findComment(issue, commentId);
        if (!comment.getAuthor().getId().equals(userId)) {
            throw new ForbiddenException("Solo el autor puede editar el comentario");
        }
        // Solo se avisa a quienes se mencionan por primera vez en este comentario.
        Set<UUID> newMentions = new LinkedHashSet<>(MentionParser.userIds(request.body()));
        newMentions.removeAll(MentionParser.userIds(comment.getBody()));

        comment.setBody(request.body().trim());
        CommentResponse response = mapper.toCommentResponse(commentRepository.saveAndFlush(comment));
        notificationService.notifyMentioned(issue, comment.getAuthor(), comment,
                mentionable(project, comment.getAuthor(), newMentions));
        return response;
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID issueId, UUID commentId, UUID userId) {
        Comment comment = findComment(findIssue(lookup.project(slug, projectId), issueId), commentId);
        boolean isAuthor = comment.getAuthor().getId().equals(userId);
        boolean isManager = workspaceMemberRepository.findRole(slug, userId)
                .map(WorkspaceRole::isManager)
                .orElse(false);
        if (!isAuthor && !isManager) {
            throw new ForbiddenException("Solo el autor o el project manager pueden borrar el comentario");
        }
        commentRepository.delete(comment);
    }

    /**
     * De los usuarios mencionados, los que pueden ver la tarea (miembros del proyecto o project managers del
     * workspace), sin contar al autor. Los ids de personas ajenas al proyecto se ignoran.
     */
    private List<User> mentionable(Project project, User author, Set<UUID> mentionedIds) {
        Set<UUID> ids = new LinkedHashSet<>(mentionedIds);
        ids.remove(author.getId());
        if (ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findMentionable(ids, project.getId(), project.getWorkspace().getId());
    }

    private Issue findIssue(Project project, UUID issueId) {
        return issueRepository.findByIdAndProjectId(issueId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
    }

    private Comment findComment(Issue issue, UUID commentId) {
        return commentRepository.findByIdAndIssueId(commentId, issue.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Comentario no encontrado"));
    }

    private static String excerpt(String body) {
        return body.length() <= ACTIVITY_EXCERPT_LENGTH ? body : TextCut.prefix(body, ACTIVITY_EXCERPT_LENGTH) + "…";
    }
}
